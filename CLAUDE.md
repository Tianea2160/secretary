# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot 3.5 기반의 AI 비서(secretary) 애플리케이션. Kotlin 2.3 + JDK 25 toolchain으로 구성되어 있으며, **두 가지 AI 통합 방식이 공존**한다:

- **Koog Agents** (`ai.koog:koog-agents`) — `main()`의 `runBlocking` 블록에서 직접 `AIAgent`를 인스턴스화해 호출
- **Spring AI Google GenAI Starter** (`spring-ai-starter-model-google-genai`) — `application.yaml`로 설정된 자동 구성

두 경로가 같은 Gemini 모델 패밀리를 사용하지만 **설정 키와 모델명이 분리되어 있다**:
- Koog: `GOOGLE_API_KEY` env + `Gemini2_5Flash` (코드 하드코딩)
- Spring AI: `GOOGLE_GENAI_API_KEY` env + `gemini-2.0-flash` (yaml)

새 기능을 추가할 때는 둘 중 어느 경로에 통합할지 먼저 결정할 것. Spring 빈으로 주입받아 쓰는 일반 서비스 코드는 Spring AI 쪽이 자연스럽고, 에이전트 워크플로/툴 호출은 Koog 쪽이 적합하다.

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

- `SecretaryApplication.kt:17`에 **하드코딩된 Google API 키 fallback**이 있음. 커밋 전에 노출 위험을 확인할 것 (`System.getenv("GOOGLE_API_KEY") ?: "AIza..."`).
- Koog와 Spring AI가 **서로 다른 환경변수 이름**을 요구한다. 둘 다 사용하려면 `GOOGLE_API_KEY`와 `GOOGLE_GENAI_API_KEY`를 모두 설정해야 한다. 통일하려면 양쪽 모두를 수정해야 함.
- `main()`에서 `runApplication` 직후 `runBlocking`으로 에이전트를 1회 호출하고 출력만 한다. 이 코드는 데모 성격이며, 실제 진입점/요청 처리 흐름이 아직 정립되지 않았다.
