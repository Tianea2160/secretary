# 시스템 아키텍처 개요

## 목적

Telegram 메시지가 들어와 AI 응답으로 나가기까지의 흐름과 각 패키지의 역할을 한눈에 보여준다. 세부 설계는 `docs/` 하위 개별 문서로 링크하므로 여기서 중복 서술하지 않는다.

## 설계 — 요청 한 건의 처리 흐름

```
[Telegram Long Polling]
        │ Update
        ▼
[SecretaryBot]                              ← @ConditionalOnExpression(bot-token != "")
        │
        ▼
[UpdateRouter] ── allowedChatIds 확인 ─→ 거부 시 warn 로그
        │ 허용
        ▼
[가상 스레드 워커]                          ← newThreadPerTaskExecutor(Thread.ofVirtual())
        │
        ▼
[SlashCommandCatalog] (text가 '/'로 시작)
        │ 또는
        ▼
[SessionService.currentOrNew(chatId)] → TSID sessionId
        │
        ▼
[AssistantRunner.run(prompt, chatId, sessionId)]
        │
        ▼
[AssistantAgentFactory.create()] ── 호출마다 새 AIAgent
        │
        ▼
[AIAgent (Koog)]
   ├─ install(ChatMemory)        → Postgres SPRING_AI_CHAT_MEMORY
   ├─ install(LongTermMemory)    → PgVector vector_store
   ├─ install(OpenTelemetry)     → Langfuse OTLP/HTTP
   └─ strategy = chatStrategy()  → preprocess → retrieveKnowHow → callLLM → emitText → reflect → consolidate
        │ 응답 텍스트
        ▼
[TelegramMessageSender] → LatexUnicodeRenderer → TelegramMarkdownRenderer → 4000자 청킹
        │
        ▼
[Telegram sendMessage]
```

**주요 결정 두 가지**:
- `UpdateRouter`는 폴링 스레드를 막지 않으려고 모든 update를 **가상 스레드 풀**에 위임한다. LLM 응답 지연이 폴링 루프에 영향을 주지 않는다 (`UpdateRouter.kt:34-37`).
- `AssistantAgentFactory`는 호출마다 **새 `AIAgent` 인스턴스**를 만든다. Koog `OpenTelemetry` feature의 `SpanCollector`가 인스턴스 단위로 span tree를 관리해서, 단일 인스턴스를 동시 호출하면 race(`Error deleting span node`)가 난다 (`AssistantRunner.kt:11-14`).

## 모듈 레이아웃

| 패키지 | 역할 | 핵심 파일 |
|---|---|---|
| `org.tianea.secretary` | Spring Boot 진입점 (현재 `main()`은 컨텍스트 부팅만) | `SecretaryApplication.kt` |
| `.config` | Koog `AIAgent` factory 빈 조립, OpenTelemetry exporter, LLModel 라우팅 | `AgentConfig.kt` |
| `.core.agent` | 에이전트 호출 facade와 strategy graph | `AssistantRunner.kt`, `AssistantAgentFactory.kt`, `graph/ChatStrategyConfig.kt` |
| `.core.agent.knowhow` | 노하우 메모리 — 추출(reflect)·중복 판정(consolidate)·검색(retrieve) | `KnowHowStore.kt`, `KnowHowReflector.kt`, `KnowHowConsolidator.kt`, `KnowHowEntity.kt` |
| `.core.scheduling` | Quartz 기반 도메인 스케줄러 (cron / interval) | `ScheduleService.kt`, `AgentExecutionJob.kt`, `SchedulingTools.kt` |
| `.core.session` | 세션 식별과 슬래시 명령 카탈로그 | `SessionService.kt`, `SlashCommandCatalog.kt` |
| `.telegram` | Telegram I/O, MarkdownV2 직렬화, 4000자 청킹 | `SecretaryBot.kt`, `UpdateRouter.kt`, `TelegramMessageSender.kt` |
| `.telegram.latex` | LaTeX `$...$` → 유니코드 수식 단일 패스 파이프라인 | `LatexLexer.kt`, `LatexParser.kt`, `UnicodeMathRenderer.kt` |

각 영역의 깊은 설명:

- 메모리 세 계층 → [chat-memory.md](./chat-memory.md) (단기), [long-term-memory.md](./long-term-memory.md) (장기 의미검색), [know-how-memory.md](./know-how-memory.md) (절차적 노하우)
- Koog DSL → [koog-strategy-graph.md](./koog-strategy-graph.md)
- 두 AI 스택 선택 기준 → [koog-vs-spring-ai.md](./koog-vs-spring-ai.md)
- 환경변수·yaml·compose → [configuration.md](./configuration.md)
- TUI 백엔드 → [tui-ffm.md](./tui-ffm.md)

## 외부 의존성

| 의존성 | 사용처 |
|---|---|
| PostgreSQL + pgvector | ChatMemory (JDBC), Quartz jobstore, VectorStore, Langfuse 메타데이터 |
| Ollama | 로컬 chat/embedding (기본 `phi4-mini` + `qwen3-embedding:8b`, host:11434) |
| Google Generative AI | Gemini 호출 — `spring.ai.model.chat=google-genai`일 때 활성 |
| Langfuse (self-hosted) | OTLP/HTTP trace 수신 (`AssistantAgentFactory`의 `addLangfuseExporter()`) |
| Telegram Bot API | Long Polling 수신 + `sendMessage` 송신 |
| Quartz | JDBC jobstore 기반 스케줄러 (`spring-boot-starter-quartz`) |

서비스 포트와 자격증명은 [configuration.md](./configuration.md#docker-compose-서비스) 참고.

## 후속 문서 (TODO)

- Telegram ingress 상세 (`UpdateRouter`의 chat-id 추출, graceful shutdown)
- Quartz 스케줄링 워크플로 (`registerCron` / `registerInterval`, `AgentExecutionJob` 재호출)
- 세션·슬래시 명령 확장 가이드
- LaTeX 파이프라인 (Lexer → Parser → UnicodeMathRenderer)
- Langfuse 트레이스 구조와 verbose 모드 attribute

위 다섯은 현재 1차 출처가 소스 파일 KDoc이다.

## 출처

- 코드: `SecretaryApplication.kt`, `config/AgentConfig.kt`, `core/agent/AssistantRunner.kt`, `core/agent/AssistantAgentFactory.kt`, `core/agent/graph/ChatStrategyConfig.kt`, `core/agent/graph/nodes/`, `core/agent/knowhow/`, `telegram/UpdateRouter.kt`, `telegram/TelegramMessageSender.kt`, `core/session/SessionService.kt`, `core/scheduling/ScheduleService.kt`
- 설정: [configuration.md](./configuration.md)
