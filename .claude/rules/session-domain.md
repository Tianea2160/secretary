---
paths:
  - "src/main/kotlin/org/tianea/secretary/core/session/**"
  - "src/main/kotlin/org/tianea/secretary/shell/command/**"
---

# 세션 도메인 / 슬래시 명령 카탈로그

## 단일 출처 원칙

- **세션 비즈니스 로직은 `SessionService`에만**. `ChatCommands`(line-mode `@Command`)와 `SlashCommandCatalog`(TUI) 두 dispatch surface는 모두 `SessionService`에 위임.
- 새 세션 동작 추가 시 `SessionService`에 메서드 한 개 → 두 dispatch에서 호출. 비즈니스 로직을 dispatch 측에 작성하지 말 것.

## SlashCommandCatalog 패턴

- `SlashCommand` data class에 **람다 핸들러**(`execute: (args) -> Result`)를 직접 들고 다닌다. 새 명령 추가 시 `commands` 리스트 한 곳만 수정 — `when` 분기 동기화 불필요.
- `Result(messages: List<String>, quit: Boolean = false)` — 채팅에 append할 메시지 + 종료 신호.
- `execute(rawText)`는 `/cmd arg1 arg2` 파싱 후 `commands.find { it.name == name }`으로 매칭. 미매칭 시 `[error] unknown command:` 메시지 반환.

## shell/command (line-mode dead code)

- `ChatCommands`, `SessionsTuiCommand`의 `@Command` 메서드들은 Spring Shell line-mode 셸 prompt에서만 호출되는데, 본 프로젝트는 `ChatTuiRunner`가 진입점이고 line-mode를 우회한다 → **현재 dead code**.
- 보존 이유: line-mode가 다시 활성화되거나 별도 진입점으로 재사용될 가능성. 새 기능을 여기 추가하지 말고 `SlashCommandCatalog`에 추가.
- `ChatCommands.ask`는 `agent.run`을 직접 호출하는 dead 경로. 채팅 TUI에서는 일반 입력이 자동으로 LLM 호출로 흘러가므로 `/ask` 슬래시 명령도 카탈로그에 두지 않음.

## 패키지 위치

- `core/session/`은 도메인 — UI 의존성 없음. `shell/`은 UI.
- `SlashCommandCatalog`가 `core/session/`에 있는 이유: 카탈로그 자체는 도메인(명령 정의 + 비즈니스 로직 위임)이고, popup 표시는 UI(`ChatTuiRunner`) 책임으로 분리.
