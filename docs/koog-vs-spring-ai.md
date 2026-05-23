# Koog vs Spring AI — 기능 비교 및 결합 방침

## 목적

본 프로젝트가 **Koog Agents** 와 **Spring AI 1.x** 를 동시에 의존하고 있는 배경을 정리하고, 새 기능을 어느 쪽에 붙일지 결정할 때 참고할 수 있는 매트릭스를 남긴다.

조사 시점: 2026-05 기준 — Koog `0.8.0` (Beta), Spring AI `1.1.6`.

## 두 프레임워크의 포지션 (한 줄 요약)

| | Koog | Spring AI |
|---|---|---|
| 만든 곳 | JetBrains | VMware/Spring 팀 |
| 1차 추상화 | **Agent workflow 엔진** (`AIAgent` + graph strategy) | **Spring 빈 친화적 LLM 추상화** (`ChatModel`, `ChatClient`, `VectorStore`) |
| 메타포 | LangGraph 계열 | LangChain Core 계열 |
| 안정성 | 0.8.0 **Beta**, 1.0 GA 미발표 ([CHANGELOG](https://github.com/JetBrains/koog/blob/main/CHANGELOG.md)) | 1.0 GA(2025-05) 이후 안정 ([1.0 GA 블로그](https://spring.io/blog/2025/05/20/spring-ai-1-0-GA-released/)) |
| 배포 타깃 | JVM/JS/WasmJS/Android/iOS (Kotlin Multiplatform) | JVM 전용 |

## 본 프로젝트의 결합 — 평행이 아니라 계층

`build.gradle.kts`의 의존성을 보면 두 라이브러리는 **경쟁 관계가 아니라 계층 관계**다:

```kotlin
implementation(libs.koog.agents)
implementation(libs.koog.spring.ai.starter.model.chat)        // ← 브릿지
implementation(libs.koog.spring.ai.starter.chat.memory)       // ← 브릿지
implementation(libs.spring.ai.starter.model.google.genai)
implementation(libs.spring.ai.starter.chat.memory.jdbc)
```

Koog가 제공하는 두 starter (`koog-spring-ai-starter-*`) 를 통해 Koog의 `AIAgent`가 **Spring AI의 `ChatModel`과 `ChatMemoryRepository`를 자동으로 위임받아** 사용한다. 즉:

```
[AIAgent (Koog)]                              ← agent 흐름·strategy·도구·메모리 정책
      │
      ├── PromptExecutor ────► [Spring AI ChatModel ────► Google GenAI]
      │
      └── ChatMemory ────────► [SpringAiChatHistoryProvider ────► JdbcChatMemoryRepository ────► Postgres]
```

자세한 결합 다이어그램과 동작 흐름은 [docs/chat-memory.md](./chat-memory.md) 참조.

따라서 "둘 중 하나만 쓴다"는 단순 비교는 본 프로젝트의 실제 구조와 맞지 않다. **결정해야 할 것은 "둘 중 무엇을 쓸지"가 아니라 "새 기능 코드를 어느 계층에 둘지"** 이다.

## 기능 매트릭스

| 카테고리 | Koog | Spring AI | 본 프로젝트 채택 |
|---|---|---|---|
| LLM 호출 (모델 추상화) | `PromptExecutor`, `LLMClient` | `ChatModel`, `ChatClient` | **Spring AI** (Koog가 위임) |
| 모델 카탈로그 | `GoogleModels.Gemini2_5Flash` 등 enum | yaml의 모델명 문자열 | 양쪽에 모델 ID가 분기 (CLAUDE.md 알려진 함정) |
| 단기 대화 메모리 (정책) | `install(ChatMemory) { windowSize(...) }` | `MessageWindowChatMemory` + `MessageChatMemoryAdvisor` | **Koog 정책** (windowSize 20) |
| 단기 대화 메모리 (저장소) | `ChatHistoryProvider`(브릿지로 Spring AI 위임) | `ChatMemoryRepository` (JDBC/Cassandra/Mongo/Neo4j/Cosmos) | **Spring AI JDBC** → Postgres |
| 장기 의미 검색 메모리 | 벡터 DB 기반 long-term memory 빌트인 ([docs.koog.ai](https://docs.koog.ai/)) | `VectorStore` + `VectorStoreChatMemoryAdvisor` ([docs.spring.io](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)) | **Koog `LongTermMemory` + Spring AI PgVector** (자세한 내용은 [docs/long-term-memory.md](./long-term-memory.md)) |
| 절차적 노하우 메모리 | strategy 그래프 노드 + `PromptExecutor.executeStructured`로 자체 조립 | 빌트인 추상화 없음 (직접 조립) | **Koog 그래프 노드 + Spring Data JPA + PgVector** ([docs/know-how-memory.md](./know-how-memory.md)) |
| Tool calling | `@Tool`, ToolRegistry, MCP 클라이언트 ([Koog README](https://github.com/JetBrains/koog)) | `@Tool` + `ToolCallingManager`, MCP starter ([1.0 GA](https://spring.io/blog/2025/05/20/spring-ai-1-0-GA-released/)) | **미도입** |
| Agent workflow / 분기·루프 | **Graph 기반 strategy** (node/edge/subgraph, 1급 추상화) | `ChatClient` Advisor — **선형 체인**. 1.1.0-M4 Recursive Advisor로 루프만 일부 ([Recursive Advisors](https://spring.io/blog/2025/11/04/spring-ai-recursive-advisors/)) | **Koog만 가능** (현재 단일 strategy) |
| 멀티에이전트 / handoff | A2A 프로토콜 0.5.0 ([0.5.0 release](https://github.com/JetBrains/koog/releases/tag/0.5.0)) | 공식 추상화 없음 — Effective Agents 레시피 + 커뮤니티 모듈 ([Effective Agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html)) | **Koog가 우위** |
| Agent 상태 직렬화·체크포인트·다른 머신 복원 | 빌트인 ([docs.koog.ai](https://docs.koog.ai/)) | **없음** | **Koog만 가능** |
| Tracing / Observability | OTel + Langfuse / W&B Weave / Datadog exporter ([Part 3 블로그](https://blog.jetbrains.com/ai/2025/12/building-ai-agents-in-kotlin-part-3-under-observation/)) | Micrometer + OTel (ChatClient 단위) | **미도입** |
| RAG | 직접 조립 | `QuestionAnswerAdvisor` + `VectorStore` 빌트인 | **미도입** — 도입 시 Spring AI 우위 |
| Spring 빈 라이프사이클 | `runBlocking`/`suspend` 위주 — 빈 통합은 가능하나 자연스럽지 않음 | 네이티브 — `@Bean`, `@Configuration`, properties 통합 | **양쪽 혼용** (`AgentConfig`는 둘 다 빈으로 노출) |
| KMP 배포 (iOS/Android/Wasm) | 지원 | **없음** | 해당 사항 없음 (서버 단일 타깃) |

## 카테고리별 결정 가이드

새 기능을 추가할 때 어디에 둘지 빠르게 판단하기 위한 결정 기준.

### 1. LLM 호출만 필요한 단순 기능 → Spring AI

예: "임베딩 한 번 뽑아서 DB에 저장", "단발 분류 호출".

`ChatClient` / `EmbeddingModel`을 빈으로 주입받아 처리. Koog `AIAgent`로 감쌀 이유 없음. Koog의 graph 추상화를 쓰지 않으면 오버헤드만 늘어난다.

### 2. 멀티턴 대화·도구 호출 흐름 → Koog `AIAgent`

예: "사용자 질문에 따라 다른 도구를 골라 호출하는 비서 흐름".

Koog의 strategy graph로 의도 분류 → 도구 분기 → 응답 합성을 표현. 현재 `assistantAgent` 빈이 이 자리.

### 3. 의미 검색 기반 장기기억 → Koog `LongTermMemory` + Spring AI `VectorStore`

본 프로젝트는 **Koog `install(LongTermMemory)` + Spring AI PgVector** 조합을 채택했다. ChatMemory와 동일한 계층(Koog feature 정책 + Spring AI 인프라)에 두어 일관성을 유지. 단, Koog 0.8.0의 `LongTermMemory`는 `@ExperimentalAgentsApi` 표기라 minor 업그레이드 시그니처 변경 리스크 존재.

순수 Spring AI 패턴(`VectorStoreChatMemoryAdvisor` + ChatClient)도 동등한 표현력을 가지므로, Koog 의존을 빼는 시점이 오면 그쪽으로 이전 가능. 자세한 결합은 [docs/long-term-memory.md](./long-term-memory.md), 매핑은 [장기기억 매핑](#장기기억-매핑-langchain-basestore--spring-ai) 참고.

### 4. 분기·루프·체크포인트가 필요한 워크플로 → Koog 유지

Spring AI Advisor는 선형 체인이라 동등 표현 불가. 1.1 Recursive Advisors로 루프는 일부 가능하지만 graph는 없다. 이 경우 Koog 0.x Beta 리스크를 감수.

### 5. Spring 빈 라이프사이클·트랜잭션과 강하게 얽힌 코드 → Spring AI

`@Transactional`, `@EventListener`, AOP 등과 결합되는 비즈니스 로직 안에서 LLM을 부르는 케이스. `runBlocking`을 가운데 끼우지 말고 `ChatClient` 직접 호출.

## 장기기억 매핑 (LangChain `BaseStore` → Spring AI)

LangChain의 `BaseStore` 같은 단일 키-값 store 추상화는 **Spring AI에 별도로 존재하지 않는다.** 대신 다음 조합으로 대체한다:

| LangChain 패턴 | Spring AI 대응 |
|---|---|
| `(namespace, key) → value` 키-값 저장 | `VectorStore` + 메타데이터 필드 |
| 의미 검색 | `VectorStore.similaritySearch(filterExpression)` |
| 사용자별 분리 | metadata에 `userId` 등록 후 `b.eq("userId", id)` 필터 |
| Prompt 자동 주입 | `VectorStoreChatMemoryAdvisor` (`<memory-entry>` 태그로 시스템 메시지에 삽입) |
| 대화 히스토리 그대로 보존 | `ChatMemoryRepository` 구현체 (이미 도입됨) |

핵심 코드 패턴:

```kotlin
ChatClient.builder(chatModel)
    .defaultAdvisors(VectorStoreChatMemoryAdvisor.builder(vectorStore).build())
    .build()
    .prompt().user(input)
    .advisors { it.param(ChatMemory.CONVERSATION_ID, userId) }
    .call().content()
```

**주의** — `VectorStoreChatMemoryAdvisor`는 텍스트 전용이고 멀티모달·tool 호출 중간 메시지는 저장하지 않는다 ([Advisors API](https://docs.spring.io/spring-ai/reference/api/advisors.html)). 도구 흐름까지 포함한 장기기억이 필요하면 직접 구현 필요.

## 결정적 차별점 — Spring AI로 안 되는 것

다음 기능이 필요하다면 Koog 의존을 유지해야 한다:

1. **Graph 기반 strategy** — 분기·루프·병렬·subgraph composition을 선언적으로 표현
2. **Agent 상태 체크포인트·롤백·다른 머신에서 복원** — fault tolerance가 중요한 장시간 워크플로
3. **Kotlin Multiplatform 배포** — 동일 agent 코드를 모바일/Wasm에서 실행 (현재 본 프로젝트엔 해당 없음)

이외 영역은 Spring AI로 동등하거나 더 풍부하다.

## 알려진 통합 함정

본 프로젝트에서 두 라이브러리를 함께 쓰면서 부딪힌 실제 함정 목록.

### 1. `kotlinx-serialization` 버전 다운그레이드

Koog 0.8.0은 `kotlinx-serialization 1.8.1`로 컴파일됐는데, Spring Boot의 `dependency-management` 플러그인이 BOM에 따라 `1.6.3`으로 다운그레이드해서 런타임 `AbstractMethodError` 발생.

해결: `build.gradle.kts:26-31`에서 `extra["kotlin-serialization.version"]`로 강제 오버라이드. 코루틴도 동일.

### 2. 환경변수·모델명 분기

Koog와 Spring AI가 같은 Gemini 모델 패밀리를 쓰지만 키와 모델명이 분리됨:

- Koog: `GOOGLE_API_KEY` env + `Gemini2_5Flash` (코드 하드코딩)
- Spring AI: `GOOGLE_GENAI_API_KEY` env + `gemini-2.0-flash` (yaml)

본 프로젝트는 `koog-spring-ai-starter-model-chat` 브릿지로 Koog가 Spring AI ChatModel을 위임받아 쓰므로 **현재는 Spring AI 쪽 키만 살아있고 Koog 키는 미사용 상태**일 수 있음. `SecretaryApplication.kt:17`에 남아있는 하드코딩 fallback은 노출 위험으로 정리 대상.

### 3. ChatMemory 브릿지의 silent drop

`SpringAiChatHistoryProvider`는 plain text System/User/Assistant 메시지만 영속한다. Koog의 tool call/result, reasoning 단계, 첨부는 **무음으로 누락**된다. 도구 사용 에이전트로 확장 시 히스토리만 보고 재현 불가. 자세한 내용은 [docs/chat-memory.md "알려진 제약"](./chat-memory.md) 섹션.

### 4. Koog Beta 리스크

`0.8.0` 시점이라 minor 업그레이드마다 API 변경 가능성 있음. 핵심 비즈니스 로직을 Koog strategy에 깊이 의존시키기 전에 1.0 GA 일정 확인 권장.

## 참고 — 본 프로젝트가 사용하지 않는 기능

이 매트릭스가 다루지만 현재 도입하지 않은 영역들. 도입 시점에 이 문서를 갱신할 것.

- Tool calling / MCP
- Tracing / OTel exporter
- RAG (`QuestionAnswerAdvisor`) — 외부 문서/지식베이스 retrieval 용. 장기기억과는 다른 용도.
- 멀티에이전트 / A2A

## 출처

- [Koog Overview — docs.koog.ai](https://docs.koog.ai/)
- [JetBrains/koog GitHub](https://github.com/JetBrains/koog)
- [Koog 0.5.0 release (A2A 도입)](https://github.com/JetBrains/koog/releases/tag/0.5.0)
- [Koog × A2A 블로그](https://blog.jetbrains.com/ai/2025/10/koog-a2a-building-connected-ai-agents-in-kotlin/)
- [Building AI Agents in Kotlin Part 3 — Observation](https://blog.jetbrains.com/ai/2025/12/building-ai-agents-in-kotlin-part-3-under-observation/)
- [Spring AI 1.0 GA Released](https://spring.io/blog/2025/05/20/spring-ai-1-0-GA-released/)
- [Spring AI Reference — Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [Spring AI Reference — Vector Databases](https://docs.spring.io/spring-ai/reference/api/vectordbs.html)
- [Spring AI Reference — Advisors API](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [Spring AI Reference — Effective Agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html)
- [Spring AI Recursive Advisors](https://spring.io/blog/2025/11/04/spring-ai-recursive-advisors/)
- [Agent Memory with Spring AI & Redis (foojay.io)](https://foojay.io/today/agent-memory-with-spring-ai-redis/)
- 본 프로젝트 관련 문서: [docs/chat-memory.md](./chat-memory.md), [docs/long-term-memory.md](./long-term-memory.md), [docs/know-how-memory.md](./know-how-memory.md)
- 본 프로젝트 관련 코드: `build.gradle.kts`, `src/main/kotlin/org/tianea/secretary/config/AgentConfig.kt`, `src/main/kotlin/org/tianea/secretary/SecretaryApplication.kt`
