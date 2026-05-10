# 채팅 메모리 — 세션별 단기기억 저장소

## 목적

LLM 호출 간에 **대화 히스토리를 세션별로 격리·영속**해서 멀티턴 컨텍스트를 유지한다. 사용자가 동일 세션에서 후속 질문을 던지면 이전 메시지가 자동으로 프롬프트에 prepend 된다.

## 설계 — 두 프레임워크의 메모리 추상화 결합

본 프로젝트는 **Koog `AIAgent`** 가 LLM 호출을 담당하고, **Spring AI `ChatMemoryRepository`** 가 영속 저장소를 담당하는 하이브리드 구조다. 이 두 추상화는 모델이 다르므로 브릿지가 필요하다.

### 두 프레임워크의 차이

| | Koog ChatMemory | Spring AI ChatMemory |
|---|---|---|
| 통합 지점 | `AIAgent { install(ChatMemory) {…} }` | `ChatClient.builder().defaultAdvisors(...)` |
| 세션 식별 | `agent.run(input, sessionId)` 두 번째 인자 | `param(ChatMemory.CONVERSATION_ID, id)` |
| 추상화 단계 | `ChatHistoryProvider` 단일 | `ChatMemory`(정책) + `ChatMemoryRepository`(저장) 2단 |
| 자동 저장/로드 | `interceptStrategyStarting`/`Completed` 훅 | Advisor 체인이 가로채서 처리 |
| 기본 백엔드 | `InMemoryChatHistoryProvider` (휘발) | `MessageWindowChatMemory` + 메모리 (휘발) |
| Postgres 연동 | 직접 구현 또는 브릿지 | `JdbcChatMemoryRepository` 표준 제공 |

### 채택한 구조

```
[AIAgent.run(input, tsid)]
        │
        ▼
[install(ChatMemory)] ── interceptStrategyStarting/Completed ──> [ChatHistoryProvider]
                                                                          │
                                                       (Koog 0.8.0 브릿지) ▼
                                                              [SpringAiChatHistoryProvider]
                                                                          │
                                                                          ▼
                                                            [Spring AI ChatMemoryRepository]
                                                                          │
                                                                          ▼
                                                          [JdbcChatMemoryRepository → Postgres]
```

각 단계는 **자동 구성**으로 연결된다 — 코드에서 직접 와이어링하는 것은 `AIAgent`에 ChatMemory를 install하는 한 곳뿐.

## 동작 흐름

1. 사용자가 셸에서 `ask 내 이름은 김철수야` 입력
2. `ChatCommands.ask()` 가 `SessionState`에서 현재 TSID(또는 새로 생성) 획득
3. `agent.run(question, sessionId)` 호출 — Koog는 sessionId를 `runId`로 사용
4. ChatMemory feature의 `interceptStrategyStarting`이 `chatHistoryProvider.load(runId)` 호출 → Postgres에서 이전 메시지 조회 → 현재 프롬프트에 prepend
5. LLM 호출 (Spring AI ChatModel을 Koog가 LLMClient로 래핑)
6. 응답 후 `interceptStrategyCompleted`가 누적된 메시지를 `chatHistoryProvider.store(runId, …)`로 영속화

## 구현 요소

### 의존성

```toml
# gradle/libs.versions.toml
[libraries]
koog-spring-ai-starter-chat-memory   = { module = "ai.koog:koog-spring-ai-starter-chat-memory" }
spring-ai-starter-chat-memory-jdbc   = { module = "org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc" }
spring-boot-starter-jdbc             = { module = "org.springframework.boot:spring-boot-starter-jdbc" }
postgresql                           = { module = "org.postgresql:postgresql" }
hypersistence-tsid                   = { module = "io.hypersistence:hypersistence-tsid" }
```

```kotlin
// build.gradle.kts
implementation(libs.koog.spring.ai.starter.chat.memory)
implementation(libs.spring.ai.starter.chat.memory.jdbc)
implementation(libs.spring.boot.starter.jdbc)
implementation(libs.hypersistence.tsid)
runtimeOnly(libs.postgresql)
```

### 데이터베이스 — Postgres 18

`docker-compose.yml`:
```yaml
services:
  postgres:
    image: postgres:18
    ports: ["5435:5432"]
    volumes: ["pgdata:/var/lib/postgresql"]   # PG18부터 PGDATA가 /var/lib/postgresql/18/docker
    environment:
      POSTGRES_DB: secretary
      POSTGRES_USER: secretary
      POSTGRES_PASSWORD: secretary
```

`application.yaml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5435/secretary
    username: secretary
    password: secretary
  ai:
    chat:
      memory:
        repository:
          jdbc:
            initialize-schema: always   # 외부 Postgres엔 자동 생성 안 되므로 명시
secretary:
  chat:
    memory:
      window-size: 20                   # 프롬프트에 실을 최근 메시지 수
```

### 자동 생성 스키마

Spring AI starter가 첫 부팅 시 다음을 생성:

```sql
CREATE TABLE SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER','ASSISTANT','SYSTEM','TOOL')),
    timestamp TIMESTAMP NOT NULL
);
CREATE INDEX SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
    ON SPRING_AI_CHAT_MEMORY (conversation_id, timestamp);
```

### Agent 설정

```kotlin
// src/main/kotlin/org/tianea/secretary/config/AgentConfig.kt
@Configuration
class AgentConfig {
    @Bean
    fun assistantAgent(
        promptExecutor: PromptExecutor,
        historyProvider: ChatHistoryProvider,                          // Koog 브릿지가 자동 등록
        @Value("\${secretary.chat.memory.window-size}") windowSize: Int,
    ): AIAgent<String, String> = AIAgent(
        promptExecutor = promptExecutor,
        llmModel = GoogleModels.Gemini2_5Flash,
        systemPrompt = "...",
    ) {
        install(ChatMemory) {
            chatHistoryProvider = historyProvider
            windowSize(windowSize)
        }
    }
}
```

### 세션 식별 — TSID

[hypersistence-tsid](https://github.com/vladmihalcea/hypersistence-tsid)의 `TSID.fast()` 로 sortable 64-bit 식별자를 생성. UUID보다 짧고 (13자) 시간순 정렬 가능.

```kotlin
// src/main/kotlin/org/tianea/secretary/shell/SessionState.kt
@Component
class SessionState {
    private val ref = AtomicReference<String?>(null)
    val current: String? get() = ref.get()

    fun newSession(): String = TSID.fast().toString().also { ref.set(it) }
    fun currentOrNew(): String = ref.get() ?: newSession()
    fun set(sessionId: String) { ref.set(sessionId) }
}
```

### 셸 명령

```kotlin
// src/main/kotlin/org/tianea/secretary/shell/ChatCommands.kt
@Command(name = ["ask"])
fun ask(
    @Option(longName = "session", shortName = 's') session: String?,
    @Arguments prompt: Array<String>?,
): String { ... }

@Command(name = ["session", "new"])     fun sessionNew(): String
@Command(name = ["session", "current"]) fun sessionCurrent(): String
@Command(name = ["session", "use"])     fun sessionUse(@Option(longName = "id") id: String): String
```

## 사용 예

```
shell:> session new
New session: 0J9KTZ5XQ2K8M

shell:> ask 내 이름은 김철수야
[session=0J9KTZ5XQ2K8M]
안녕하세요, 김철수님.

shell:> ask 내 이름이 뭐였지?
[session=0J9KTZ5XQ2K8M]
김철수입니다.                         ← Postgres에서 이전 메시지 로드

shell:> session new
New session: 0J9KU4M3ZP9F1

shell:> ask 내 이름이 뭐였지?
[session=0J9KU4M3ZP9F1]
모르겠습니다.                         ← 새 세션 → 격리됨

shell:> session use --id 0J9KTZ5XQ2K8M
Switched to session: 0J9KTZ5XQ2K8M
```

## 알려진 제약

### 1. 브릿지의 silent drop

`SpringAiChatHistoryProvider`는 **plain text System/User/Assistant 메시지만 영속**한다. Koog의 다음 정보는 무음으로 누락된다:
- Tool call/result
- Reasoning 단계
- 첨부 파일

향후 도구 사용 에이전트로 확장 시 히스토리만 보고 재현이 불가능할 수 있음. 이 경우 Koog의 Pure-JDBC `ChatHistoryProvider` 직접 구현이 필요하다.

### 2. Window size는 프롬프트 폭주 방지용

`windowSize(20)`은 **LLM에 보낼 메시지 수**를 제한할 뿐, **DB에는 모든 메시지가 누적**된다. 장기 대화 시 행 수 증가에 대비한 보존 정책(주기적 archive/truncate)은 현재 없음.

### 3. Spring DM 버전 충돌 주의

Koog 0.8.0은 `kotlinx-serialization 1.8.1`로 컴파일되어 있고, Spring Boot의 `dependency-management` 플러그인이 1.6.3으로 다운그레이드하면 `AbstractMethodError`가 발생한다. 본 프로젝트는 `build.gradle.kts`에서 `extra["kotlin-serialization.version"] = libs.versions.kotlinx.serialization.get()`로 오버라이드 되어 있음.

## 출처

- [Koog ChatMemory feature 문서](https://docs.koog.ai/features/chat-memory/)
- [Koog Spring AI integration 문서](https://docs.koog.ai/spring-ai-integration/)
- [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [Spring AI 1.1.6 release blog](https://spring.io/blog/2026/05/08/spring-ai-1-0-7-1-1-6-2-0-0-M6-available-now/)
- [JdbcChatMemoryRepository Postgres 스키마 SQL](https://github.com/spring-projects/spring-ai/blob/main/memory/repository/spring-ai-model-chat-memory-repository-jdbc/src/main/resources/org/springframework/ai/chat/memory/repository/jdbc/schema-postgresql.sql)
- 관련 코드: `src/main/kotlin/org/tianea/secretary/config/AgentConfig.kt`, `src/main/kotlin/org/tianea/secretary/shell/SessionState.kt`, `src/main/kotlin/org/tianea/secretary/shell/ChatCommands.kt`, `docker-compose.yml`, `src/main/resources/application.yaml`
