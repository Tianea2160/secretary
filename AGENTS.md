# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

Spring Boot 3.5 기반의 AI 비서(secretary) 애플리케이션. Kotlin 2.3 + JDK 25 toolchain으로 구성되어 있으며, **Koog Agents와 Spring AI 1.x를 계층으로 결합**한다:

- **Koog Agents** (`ai.koog:koog-agents`) — `AssistantRunner` → `AssistantAgentFactory.create()`가 호출마다 새 `AIAgent`를 만들어 `ChatStrategyConfig`의 그래프 전략으로 실행. `PromptExecutor`는 `koog-spring-ai-starter-model-chat` 브릿지로 Spring AI `ChatModel`을 그대로 위임받는다.
- **Spring AI** — chat·embedding·vector store·JDBC chat memory를 `application.yaml`로 자동 구성.

chat·embedding 모두 **Ollama 단일 provider**다 (`spring.ai.model.chat: ollama`, `embedding: ollama`):
- Chat: `qwen3:4b-instruct-2507-q4_K_M`
- Embedding: `qwen3-embedding:8b` (4096차원)

새 기능을 추가할 때는 어느 계층에 둘지 먼저 결정할 것. Spring 빈으로 주입받아 쓰는 일반 서비스 코드는 Spring AI 쪽이 자연스럽고, 에이전트 워크플로/툴 호출은 Koog 쪽이 적합하다.

## Common Commands

```bash
./gradlew build                                # 전체 빌드 + 테스트
./gradlew bootRun                              # 애플리케이션 실행
./gradlew test                                 # 모든 테스트 실행 (JUnit Platform, maxParallelForks=4)
./gradlew test --tests "org.tianea.secretary.SecretaryApplicationTests.contextLoads"  # 단일 테스트
./gradlew clean build -x test                  # 테스트 제외 빌드
```

JDK 25 toolchain이 필수 (`.java-version`, `build.gradle.kts`). Gradle wrapper가 toolchain을 자동 프로비저닝하므로 시스템 Java 버전과 무관하게 동작해야 한다.

## Code Conventions

- Kotlin 컴파일러 옵션: `-Xjsr305=strict`, `-Xannotation-default-target=param-property`
  - JSR305 nullability 어노테이션이 strict로 처리됨 → 외부 Java API의 `@Nullable`/`@NonNull`을 무시하면 컴파일 에러
  - `param-property` 모드 → 생성자 파라미터의 어노테이션이 자동으로 property에 함께 적용됨
- 의존성 BOM: `spring-ai-bom:1.1.6`, `kotlinx-coroutines-bom:1.11.0-rc02` — Spring AI / Coroutines 모듈 버전을 직접 지정하지 말 것
- 버전 카탈로그(`gradle/libs.versions.toml`)에 일부만 등록되어 있음. 새 의존성 추가 시 카탈로그를 우선 확장하는 방향이 컨벤션이지만 강제되지는 않는다.

## Known Pitfalls

- **koog 버전 분리**: `koog-agents`(umbrella)는 `1.0.0` stable이지만 `koog-spring-ai-starter-*`와 `agents-features-longterm-memory`는 `1.0.0-beta`만 게시돼 있다. `libs.versions.toml`은 `koog`(stable) / `koog-beta` 두 키로 나눠 관리하며, LTM은 stable umbrella가 더 이상 transitive로 포함하지 않아 의존성으로 명시 선언한다. koog 버전을 올릴 때 두 키를 함께 확인할 것.
- **Ollama 단일 provider**: chat·embedding 모두 Ollama이므로 `spring.ai.model.{chat,embedding}=ollama`를 명시해야 자동구성 충돌(provider 빈 중복)이 없다. host의 Ollama(`localhost:11434`)에 `qwen3:4b-instruct-2507-q4_K_M`·`qwen3-embedding:8b`가 떠 있어야 한다.

## Tracing

Koog의 `OpenTelemetry` feature + `addLangfuseExporter()`로 self-hosted Langfuse v3에 OTLP/HTTP trace를 보낸다 (`AgentConfig.kt`). Langfuse 스택은 `docker-compose.yml`의 web/worker + clickhouse/minio/redis로 구성되며 `LANGFUSE_BASE_URL`, `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY` 환경변수로 연결한다. `secretary.tracing.verbose=true`로 켜면 prompt/completion 본문이 span attribute에 포함된다(기본 off — 대형 컨텍스트에서 메모리·네트워크 비용 큼).
