# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot 3.5 기반의 AI 비서(secretary) 애플리케이션. Kotlin 2.3 + JDK 25 toolchain으로 구성되어 있으며, **Koog Agents와 Spring AI 1.x를 계층으로 결합**한다:

- **Koog Agents** (`ai.koog:koog-agents`) — `AssistantRunner` → `AssistantAgentFactory.create()`가 호출마다 새 `AIAgent`를 만들어 `ChatStrategyConfig`의 그래프 전략으로 실행. `PromptExecutor`는 `koog-spring-ai-starter-model-chat` 브릿지로 Spring AI `ChatModel`을 그대로 위임받는다.
- **Spring AI** — chat·embedding·vector store·JDBC chat memory를 `application.yaml`로 자동 구성.

chat·embedding 모두 **Ollama 단일 provider**다 (`spring.ai.model.chat: ollama`, `embedding: ollama`):
- Chat: `qwen3:4b-instruct-2507-q4_K_M`
- Embedding: `qwen3-embedding:8b` (4096차원)

새 기능을 추가할 때는 어느 계층에 둘지 먼저 결정할 것. Spring 빈으로 주입받아 쓰는 일반 서비스 코드는 Spring AI 쪽이 자연스럽고, 에이전트 워크플로/툴 호출은 Koog 쪽이 적합하다. 자세한 결합 방침: `docs/koog-vs-spring-ai.md`.

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

- Kotlin 컴파일러 옵션: `-Xjsr305=strict` + `allWarningsAsErrors=true`
  - JSR305 nullability 어노테이션이 strict로 처리됨 → 외부 Java API의 `@Nullable`/`@NonNull`을 무시하면 컴파일 에러
  - 생성자 파라미터 어노테이션의 param-property 타깃팅은 Kotlin 2.4 기본값이라 명시 플래그(`-Xannotation-default-target`)는 제거됨
  - `allWarningsAsErrors` → 미사용 private 멤버 등 컴파일러 경고가 빌드를 실패시킨다(컴파일 시점에 죽은 코드 차단). 단 public 멤버 미사용은 컴파일러가 감지하지 못함
- 의존성 BOM: `spring-ai-bom:1.1.6`, `kotlinx-coroutines-bom:1.11.0-rc02` — Spring AI / Coroutines 모듈 버전을 직접 지정하지 말 것
- 버전 카탈로그(`gradle/libs.versions.toml`)에 일부만 등록되어 있음. 새 의존성 추가 시 카탈로그를 우선 확장하는 방향이 컨벤션이지만 강제되지는 않는다.

## Known Pitfalls

- **koog 버전 분리**: `koog-agents`(umbrella)는 `1.0.0` stable이지만 `koog-spring-ai-starter-*`와 `agents-features-longterm-memory`는 `1.0.0-beta`만 게시돼 있다. `libs.versions.toml`은 `koog`(stable) / `koog-beta` 두 키로 나눠 관리하며, LTM은 stable umbrella가 더 이상 transitive로 포함하지 않아 의존성으로 명시 선언한다. koog 버전을 올릴 때 두 키를 함께 확인할 것.
- **Ollama 단일 provider**: chat·embedding 모두 Ollama이므로 `spring.ai.model.{chat,embedding}=ollama`를 명시해야 자동구성 충돌(provider 빈 중복)이 없다. host의 Ollama(`localhost:11434`)에 `qwen3:4b-instruct-2507-q4_K_M`·`qwen3-embedding:8b`가 떠 있어야 한다.

## Agent Runtime

요청 진입점은 텔레그램 `UpdateRouter`와 Quartz `AgentExecutionJob` 둘 다 `AssistantRunner.run()`으로 수렴한다.
`AssistantRunner`는 `AssistantAgentFactory.create()`로 **호출마다 새 `AIAgent`**를 만든다 — Koog `OpenTelemetry`
feature의 span tree가 인스턴스 단위라 공유 시 race가 발생하기 때문.

- **그래프 전략**: `ChatStrategyConfig`가 `@Bean`으로 `AIAgentGraphStrategy`를 제공한다. 전략은 실행 상태 없는
  청사진이라 싱글턴으로 공유하고, `AIAgent`만 호출별로 만든다. DSL 치트시트: `docs/koog-strategy-graph.md`.
- **호출별 데이터**: chatId·sessionId·messageId는 생성자 인자가 아니라 `ChatContext`(코루틴 컨텍스트 element)로
  전파된다. 그래프 노드·Koog 도구·`EventHandler` 핸들러 모두 `currentCoroutineContext()[ChatContext]`로 읽는다.
- **Koog 1.0.0 API 확인**: 공식 문서가 얇을 때 `~/.gradle/caches/modules-2/files-2.1/ai.koog/`의 jar를
  `javap`로, `*-sources.jar`를 `unzip -p`로 열어 시그니처를 직접 검증한다.

## Tracing

Koog의 `OpenTelemetry` feature + `addLangfuseExporter()`로 self-hosted Langfuse v3에 OTLP/HTTP trace를 보낸다 (`AgentConfig.kt`). Langfuse 스택은 `docker-compose.yml`의 web/worker + clickhouse/minio/redis로 구성되며 `LANGFUSE_BASE_URL`, `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY` 환경변수로 연결한다. `secretary.tracing.verbose=true`로 켜면 prompt/completion 본문이 span attribute에 포함된다(기본 off — 대형 컨텍스트에서 메모리·네트워크 비용 큼).
