# TUI 터미널 백엔드 (Spring Shell + JLine FFM)

## 목적

TUI 환경에서 한글·이모지·CJK 같은 **멀티바이트 입력을 안정적으로 처리**하기 위해 Spring Shell의 JLine FFM 백엔드를 도입.

## 배경 — 왜 FFM이 필요한가

### Spring Shell 4.0의 변화

Spring Shell 3.x까지는 starter가 JLine을 자동 포함했으나, 4.0부터 **JLine은 옵션 의존성**으로 분리됐다 ([spring-shell-core 4.0.2 pom](https://repo1.maven.org/maven2/org/springframework/shell/spring-shell-core/4.0.2/) 확인 — 직접 의존성 없음).

JLine이 classpath에 없으면 다음 fallback이 적용된다:

| 컴포넌트 | JLine 없음 (기본) | JLine 있음 |
|---|---|---|
| 입력 백엔드 | `SystemShellRunner` (`java.io.Console`) | `JLineShellAutoConfiguration` |
| Prompt | `$>` | `shell:>` (색상) |
| 자동완성·히스토리 | 없음 | 있음 |
| 멀티바이트 입력 | 플랫폼 기본 charset 의존 (한글 깨짐 발생) | JLine `Terminal.encoding()`으로 명시 가능 |

### 발생한 증상

JLine 없이 한글 입력 시 다음과 같이 명령 토큰에 `U+FFFD`(REPLACEMENT CHARACTER) 가 섞여 들어가 `Command` 매칭이 실패했다:

```
$> ask 너가 할 수 있는게 뭐야?
Command s�ask not found
```

`java.io.Console`이 stdin 바이트를 platform default charset으로 디코딩하는데, 일부 macOS/터미널 조합에서 UTF-8 multi-byte 시퀀스의 일부가 잘못 끊어지는 문제로 추정.

## 적용한 해결책

### 1. FFM starter 추가

```toml
# gradle/libs.versions.toml
[libraries]
spring-shell-starter-ffm = { module = "org.springframework.shell:spring-shell-starter-ffm", version.ref = "spring-shell" }
```

```kotlin
// build.gradle.kts
implementation(libs.spring.shell.starter.ffm)
```

### 2. JLine FFM 백엔드란?

Spring Shell이 제공하는 4가지 JLine 터미널 starter 중 하나:

| Starter | 백엔드 | 요건 |
|---|---|---|
| `spring-shell-starter-jansi` | JANSI 네이티브 라이브러리 | 별도 네이티브 .so/.dll |
| `spring-shell-starter-jna` | JNA | JVM에서 네이티브 호출 |
| `spring-shell-starter-jni` | 전통적 JNI | 직접 컴파일된 바인딩 |
| **`spring-shell-starter-ffm`** | **Java 22+ Foreign Function & Memory API** | Java 22 이상 |

본 프로젝트는 Java 25를 사용하므로 FFM이 가장 자연스럽다 — 별도 네이티브 의존성 없이 JDK 표준 API로 터미널을 제어한다.

추가된 transitive 의존성:
```
org.springframework.shell:spring-shell-starter-ffm:4.0.2
└── org.springframework.shell:spring-shell-jline:4.0.2
    └── org.jline:jline:3.30.9
└── org.jline:jline-terminal-ffm:3.30.9
    └── org.jline:jline-terminal:3.30.9
        └── org.jline:jline-native:3.30.9
```

### 3. UTF-8 명시 (`ShellConfig`)

JLine 기본값은 `Charset.defaultCharset()`이지만, 환경에 의존하지 않도록 `TerminalCustomizer` 빈으로 명시 지정:

```kotlin
// src/main/kotlin/org/tianea/secretary/config/ShellConfig.kt
@Configuration
class ShellConfig {
    @Bean
    fun utf8TerminalCustomizer(): TerminalCustomizer =
        TerminalCustomizer { builder ->
            builder.encoding(StandardCharsets.UTF_8)
        }
}
```

`JLineShellAutoConfiguration.terminal()` 빈은 `ObjectProvider<TerminalCustomizer>`를 받아 빌더 customize를 거치도록 되어있다.

## 결과

- 한글/이모지/일본어 등 모든 UTF-8 입력이 깨지지 않고 처리됨
- JLine 자동완성(Tab), 히스토리(↑↓), Ctrl-R 검색, 색상 prompt 활성화
- Prompt가 `$>` → `shell:>`로 변경됨

## 알려진 제약

- **TTY가 필수** — IDE Run 콘솔(stdin pipe)에서는 인터랙티브 모드에 진입하지 못한다. 외부 터미널(Terminal.app/iTerm/Warp)에서 실행 필요.
- **Java 22+ 필수** — FFM API가 표준화된 버전. 프로젝트는 Java 25를 toolchain으로 사용 (`build.gradle.kts`).
- **`application.yml`로는 인코딩 강제 불가** — `file.encoding`/`stdin.encoding`은 JVM 시작 시점에 읽히므로 yml 파싱 이전. JLine 인코딩만 `TerminalCustomizer` 빈으로 통제 가능.

## 출처

- [Spring Shell Reference — Terminal](https://docs.spring.io/spring-shell/reference/)
- [JLine 3.30.x Docs](https://github.com/jline/jline3)
- [JEP 454: Foreign Function & Memory API (Java 22)](https://openjdk.org/jeps/454)
- 관련 코드: `build.gradle.kts`, `src/main/kotlin/org/tianea/secretary/config/ShellConfig.kt`
