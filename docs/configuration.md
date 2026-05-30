# 설정 레퍼런스

## 목적

봇을 실행·튜닝하는 데 필요한 **환경변수, `application.yaml` 키, docker-compose 서비스**를 한 곳에 모은다. 루트 [README.md](../README.md)가 최소 셋업만 다루므로 그 외 값은 모두 여기서 확인한다.

## 환경변수

`.env.example`에 정의된 4개 키:

| 이름 | 용도 |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Telegram Long Polling 봇 토큰. 비어 있으면 `SecretaryBot` 빈이 비활성화된다 (`@ConditionalOnExpression`) |
| `TELEGRAM_ALLOWED_CHAT_IDS` | 응답 허용 chat ID 콤마 목록. `UpdateRouter.consume()`이 매 update마다 체크한다 |
| `LANGFUSE_BASE_URL` | self-hosted Langfuse OTLP/HTTP 엔드포인트 (`http://localhost:3000` 기본) |
| `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` | Langfuse 프로젝트 키 — Langfuse UI에서 발급 |

`.env.example` 복사 후 키만 채우면 된다:

```bash
cp .env.example .env
$EDITOR .env
```

## `application.yaml`

세 묶음으로 보면 된다.

### 인프라 — DB / Quartz / 로깅

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5435/secretary   # docker-compose가 5435로 노출
    username: secretary
    password: secretary
  quartz:
    job-store-type: jdbc                              # Postgres에 스케줄 영속
    jdbc.initialize-schema: always                    # 운영에선 never + 사전 마이그레이션
    properties:
      org.quartz.threadPool.threadCount: "5"          # 동시 Job 수
      org.quartz.jobStore.isClustered: "false"
logging:
  file.name: logs/secretary.log
  level.org.tianea: debug                             # 운영에선 info로 낮춤
```

### AI 모델 — chat / embedding 라우팅

`spring.ai.model.*` 두 값이 Koog `PromptExecutor`와 Spring AI `ChatClient` 양쪽의 백엔드를 한 번에 전환한다.

```yaml
spring:
  ai:
    model:
      chat: ollama          # 현재 ollama 고정
      embedding: ollama     # 현재 ollama 고정
    ollama:
      base-url: http://localhost:11434
      chat.options.model: qwen3:4b-instruct-2507-q4_K_M
      embedding.options.model: qwen3-embedding:8b     # 4096 차원 출력
      init.pull-model-strategy: when_missing
    vectorstore.pgvector:
      dimensions: 4096                                # embedding 차원과 반드시 일치
      distance-type: COSINE_DISTANCE
      index-type: NONE                                # 4096차원은 HNSW/IVFFlat 불가
    chat.memory.repository.jdbc.initialize-schema: always
```

모델 선택 기준은 [koog-vs-spring-ai.md](./koog-vs-spring-ai.md) 참고.

### 도메인 옵션 — secretary.\* / telegram.\*

```yaml
secretary:
  chat:
    memory.window-size: 20          # prompt에 prepend할 최근 메시지 수
    long-term-memory.top-k: 5       # 벡터 검색 반환 개수
  tracing.verbose: false            # true면 prompt/completion 본문을 span에 포함

know-how:
  enabled: true                     # false 시 retrieve/reflect/consolidate 세 노드가 pass-through
  retrieval:
    top-k: 5                        # 노하우 유사도 검색 상위 K
    token-budget: 1200              # 주입 노하우 합산 근사 토큰 상한
  reflection:
    min-importance: 0.5             # 이 미만 후보는 저장 안 함

telegram:
  bot-token: ${TELEGRAM_BOT_TOKEN:}
  allowed-chat-ids: ${TELEGRAM_ALLOWED_CHAT_IDS:}
```

`tracing.verbose=true`는 로컬 디버깅용. 운영에선 span attribute가 수 KB → 수 백 KB로 부풀어 Langfuse 송신 트래픽과 JVM span 버퍼가 함께 증가한다.

`know-how.*` 세부 의미와 노드 동작은 [know-how-memory.md](./know-how-memory.md) 참고.

## docker-compose 서비스

`docker compose up -d` 한 번으로 다음이 뜬다:

| 서비스 | 호스트 포트 | 역할 |
|---|---|---|
| `postgres` | 5435 | Postgres 18 + pgvector. ChatMemory, Quartz, VectorStore, Langfuse 메타데이터 공유 |
| `langfuse-web` | 3000 | Langfuse UI · API. 첫 접속 시 프로젝트 생성하고 키 발급 |
| `langfuse-worker` | 3030 | Langfuse 이벤트 처리 워커 |
| `clickhouse` | (내부 8123/9000) | Langfuse 이벤트 스토리지 |
| `minio` | 9090 / 9091 | Langfuse S3 호환 오브젝트 스토리지 (UI 9091) |
| `redis` | (내부 6379) | Langfuse 큐·캐시 |

모든 서비스 자격증명은 `secretary` / `secretary`로 하드코딩되어 있다. **운영 배포 시 반드시 교체**한다.

## 알려진 제약

- **chat·embedding 모두 로컬 Ollama이므로 LLM API 키가 필요 없다.** `localhost:11434`에 `qwen3:4b-instruct-2507-q4_K_M`(chat)·`qwen3-embedding:8b`(embedding)가 떠 있어야 첫 호출이 성공한다. `spring.ai.model.{chat,embedding}=ollama`를 명시하지 않으면 provider 자동구성이 충돌한다.
- **임베딩 모델 차원은 PgVector 스키마에 묶여 있다.** `qwen3-embedding:8b`(4096) 외 다른 모델로 바꾸면 `vector_store` 테이블의 `vector(4096)` 컬럼과 충돌한다. 모델 교체 시 기존 벡터 데이터를 비우고 재생성해야 한다.

## 출처

- `.env.example`, `docker-compose.yml`, `src/main/resources/application.yaml`
- `src/main/kotlin/org/tianea/secretary/config/AgentConfig.kt` — tracing.verbose, resolveLlmModel
- `src/main/kotlin/org/tianea/secretary/telegram/TelegramProperties.kt` — telegram.\* 바인딩
- 관련 문서: [chat-memory.md](./chat-memory.md), [long-term-memory.md](./long-term-memory.md), [know-how-memory.md](./know-how-memory.md), [koog-vs-spring-ai.md](./koog-vs-spring-ai.md)
