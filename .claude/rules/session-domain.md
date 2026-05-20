---
paths:
  - "src/main/kotlin/org/tianea/secretary/core/session/**"
---

# 세션 도메인 / 슬래시 명령 카탈로그

## 단일 출처 원칙

- **세션 비즈니스 로직은 `SessionService`에만**. dispatch surface(현재는 `SlashCommandCatalog`)는
  `SessionService`에 위임만 한다.
- `SessionService`는 그 자체로도 위임 계층 — `SessionState`(in-memory 현재 세션)와
  `SessionRepository`(영속 대화 ID)에 얇게 위임한다.
- 새 세션 동작 추가 시 `SessionService`에 메서드 한 개 → dispatch에서 호출. 비즈니스 로직을 dispatch
  측(`SlashCommandCatalog`, `UpdateRouter`)에 작성하지 말 것.

## SlashCommandCatalog 패턴

- `SlashCommand` data class에 **람다 핸들러**(`execute: (args, chatId) -> Result`)를 직접 들고 다닌다.
  새 명령 추가 시 `commands` 리스트 한 곳만 수정 — `when` 분기 동기화 불필요.
- `Result(messages: List<String>)` — dispatch surface가 사용자에게 보낼 메시지 목록.
- `execute(rawText, chatId)`는 `/cmd args` 파싱 후 `commands.find { it.name == name }`으로 매칭.
  미매칭 시 `[error] unknown command: /name` 메시지 반환.
- 텔레그램 `UpdateRouter`가 `/`로 시작하는 입력을 `SlashCommandCatalog.execute(...)`로 보낸다.

## 패키지 위치

- `core/session/`은 **도메인** — UI/전송 계층 의존성 없음.
- `SlashCommandCatalog`가 `core/session/`에 있는 이유: 카탈로그 자체는 도메인(명령 정의 + 비즈니스
  로직 위임)이고, 명령을 어디서 입력받아 어떻게 표시할지는 dispatch surface(`telegram/`) 책임으로 분리.
