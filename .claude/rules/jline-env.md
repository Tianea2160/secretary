---
paths:
  - "src/main/kotlin/org/tianea/secretary/SecretaryApplication.kt"
  - "build.gradle.kts"
  - "src/main/resources/application.yaml"
---

# JLine + JDK 25 + .env 환경 함정

## JLine provider 자동선택 회피

- JLine이 자동으로 `ExecPty`를 고르는 케이스가 있다. ExecPty는 redraw마다 `stty size` 같은 외부 프로세스를 fork-exec하는데, Spring Shell EventLoop와 결합되면 `ProcessImpl.waitFor`에서 `InterruptedIOException: Command interrupted` 발생.
- **해결**: `SecretaryApplication.main`에서 `System.setProperty("org.jline.terminal.provider", "ffm")` (또는 `.env`의 `JLINE_TERMINAL_PROVIDER` 매핑). FFM 백엔드는 `ioctl(TIOCGWINSZ)`을 native로 직접 호출.
- JLine 키 상수: `org.jline.terminal.provider`(provider 이름), `org.jline.terminal.providers`(우선순위 리스트), `org.jline.terminal.{ffm,exec,jansi,jna,jni}`(개별 enable/disable).

## JDK 24+ unnamed module native access

- JDK 24부터 unnamed module(classpath 실행)의 native access가 기본 비활성. JLine FFM provider가 `Arena`/`MemorySegment`를 쓰려고 하면 `UnsupportedOperationException: Native access is not enabled`로 실패.
- **`build.gradle.kts`에 두 곳 명시**:
  - `bootJar` manifest: `attributes["Enable-Native-Access"] = "ALL-UNNAMED"` — `java -jar` 실행 시 자동 적용
  - `bootRun` jvmArgs: `--enable-native-access=ALL-UNNAMED` — `./gradlew bootRun` 시
- IDE에서 main 클래스 직접 실행하는 Run config는 manifest를 안 보므로 **VM options에 `--enable-native-access=ALL-UNNAMED` 별도 추가** 필요.

## .env → system property 매핑

- `loadDotenvIntoSystemProperties`가 main 시작 시 `.env`의 각 entry를 `System.setProperty`로 set. JLine은 `System.getProperty`로 직접 읽고, Spring `${...}` placeholder도 system property를 우선 PropertySource로 읽으므로 동일 경로로 통합.
- **OS env가 아니라 system property가 단일 출처**. shell rc에 export하지 말고 `.env` 한 파일로 관리. 이미 set된 system property나 `-D` JVM 인자는 `.env`가 덮어쓰지 않음.
- `.env`는 `.gitignore`. `.env.example`만 커밋.

## Spring Shell ShellRunner 비활성화

- `spring.shell.interactive.enabled`는 **양자택일** (true=Interactive, false=NonInteractive). 둘 다 끄는 표준 키 없음.
- `ShellRunnerAutoConfiguration`은 일반 `@Configuration`이라 `spring.autoconfigure.exclude`로도 비활성 불가.
- **해결**: `ChatTuiRunner`를 `@Order(Ordered.HIGHEST_PRECEDENCE)` + 종료 시 `SpringApplication.exit(ctx)` + `exitProcess`로 후속 ShellRunner 차단.
