# 채팅 TUI + 슬래시 명령 자동완성 — 구현 plan

## 목표

앱 실행 시 **풀스크린 채팅 UI로 자동 진입**. 화면은 상단 히스토리(메시지 누적) + 하단 입력바(고정) 분할. 입력 첫 글자가 `/` 면 입력 변화에 따라 **명령 후보 popup**이 나타나 ↑/↓로 이동, Enter로 선택. Claude Code의 명령 자동완성과 동일한 UX.

## 결정 사항 (사용자 확정)

| 항목 | 결정 |
|---|---|
| 진입 방식 | 앱 시작 시 자동 채팅 모드 (Spring Shell의 line-based 진입을 우회) |
| 명령 카탈로그 | 기존 `@Command`를 그대로 슬래시 명령으로 매핑 (별도 카탈로그 미구축) |

## 시각적 구조

```
┌─────────────────────────────────────────────────┐  ← AppView (full-screen)
│ user: 내 이름은 김철수야                         │
│ assistant: 안녕하세요, 김철수님.                  │
│ user: /sess|                                     │  ← 메시지 흐름이 여기 누적
│  ┌─────────────────────────┐                     │
│  │ /sessions  세션 목록     │                     │  ← 슬래시 popup (modal)
│  │ /session-new            │                     │     입력 변화마다 필터링
│  │ /session-current        │                     │
│  └─────────────────────────┘                     │
├─────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────┐ │  ← 입력바 (하단 고정)
│ │ /sess▏                                      │ │     InputView
│ └─────────────────────────────────────────────┘ │
│ session=0J9KTZ5XQ2K8M │ gemini-2.5-flash       │  ← StatusBarView (선택)
└─────────────────────────────────────────────────┘
```

## 검증 필요한 핵심 미지수

각 단계 진입 전에 검증되어야 함. 실패 시 fallback은 위험 섹션 참조.

1. **Spring Shell InteractiveShellRunner 비활성화** — 셸 line-loop가 stdin을 점유하면 우리 TUI가 raw mode 진입을 못 한다. `spring.shell.interactive.enabled: false` 또는 `ApplicationRunner` 우선 등록으로 우회 가능한지.
2. **CommandRegistry 빈 + 메타데이터 조회** — Spring Shell 4.0의 `CommandRegistration` API에서 등록된 모든 명령(이름·설명·옵션)을 조회하는 정확한 메서드명. jar inspect로 확인.
3. **InputView API** — 텍스트 변경 이벤트(`onTextChange` 또는 reactor stream)를 emit하는지. 안 한다면 `keyEvents()` 직접 구독 + StringBuilder로 자체 입력 추적.
4. **modal popup** — `TerminalUI.setModal(View)`로 ListView를 InputView 위에 floating으로 띄우는 게 공식 패턴인지. 또는 GridView의 한 cell로 두고 visibility 토글이 정석인지.
5. **비동기 LLM 호출 시 view 업데이트** — `agent.run(...)`(suspend, ~수초)을 Reactor `Schedulers.boundedElastic`에서 호출 후, 결과를 EventLoop main 스레드로 dispatch하는 패턴. context propagation·thread safety 확인.

## 단계 분해

각 단계는 자체 smoke test 가능하도록 작게 끊음. 한 단계가 막히면 위험 섹션의 fallback으로 전환.

### 단계 1 — 풀스크린 채팅 진입 (POC)

**범위**: GridView 2분할 + InputView + 단순 echo. LLM 미통합.

**작업**:
- `application.yaml`에 `spring.shell.interactive.enabled: false` 추가
- `ChatTuiRunner: ApplicationRunner` 빈 추가 — TUI 진입점
- `AppView`(또는 BoxView) root 안에 `GridView(rows=10, cols=1)`
  - 상단 cell: `BoxView`(scrollable text) — 메시지 히스토리
  - 하단 cell: `InputView` — 1~2줄 높이
- InputView submit → 입력 텍스트를 BoxView에 `[user] ...` 형태로 append
- `q` 또는 `:quit` 입력 시 `ofInterrupt()` → 앱 종료

**완료 조건**: 외부 터미널(Ghostty)에서 실행 → 채팅 화면 등장 → 텍스트 입력 → 상단에 echo → q로 종료.

**검증되는 미지수**: 1, 4 일부.

### 단계 2 — LLM 흐름 통합

**범위**: 입력을 LLM으로 보내고 응답을 메시지 영역에 append.

**작업**:
- `ChatTuiRunner`에 `AIAgent<String, String>` + `SessionState` 주입
- InputView submit → 입력을 `[user]`로 append → `Schedulers.boundedElastic()`에서 `runBlocking { agent.run(text, sessionId) }` 호출 → 응답을 `EventLoop.dispatch`로 main 스레드에 보내 `[assistant]`로 append
- 호출 중에는 InputView를 비활성화 + StatusBarView에 "thinking..." 표시

**완료 조건**: 입력 → LLM 응답 → 화면 갱신. 멀티턴 컨텍스트 (ChatMemory) 동작.

**검증되는 미지수**: 5.

**위험**: 비동기 호출 중 InputView/EventLoop 동시 접근. 동기화 패턴 확립 후 진행.

### 단계 3 — 슬래시 popup

**범위**: 입력 첫 문자가 `/`이면 후보 popup. 입력 변화마다 필터.

**작업**:
- InputView 텍스트 변화 감지 (미지수 3 검증 후 패턴 결정)
- 텍스트가 `/`로 시작하면 `ListView<SlashCommand>` 생성 → `TerminalUI.setModal(list)`
- 입력 변화마다 후보 list `setItems()` 갱신 (prefix 매칭)
- ↑/↓: ListView 내부 핸들러 (이미 동작)
- Enter on ListView → 명령 invoke + popup 닫기
- Backspace로 `/`까지 지워지면 popup 자동 닫기
- `q` 단독은 popup 안에서 닫기, popup 밖에서는 종료 (key context 분리)

**완료 조건**: `/se` 입력 → `sessions`, `session-new`, `session-current` 후보 표시 → ↓로 이동 → Enter → 해당 명령 실행 → 결과를 메시지 영역에 append.

**검증되는 미지수**: 3, 4.

### 단계 4 — 명령 카탈로그 = 기존 @Command 매핑

**범위**: 슬래시 popup의 후보 데이터 출처를 Spring Shell `CommandRegistration`에서 가져옴.

**작업**:
- `CommandRegistry`(또는 동등 빈) 주입 — 정확한 API는 미지수 2 검증 후 결정
- 등록된 모든 명령에서 `(name, description)` 추출 → `List<SlashCommand>` 변환
- 명령 invoke 시 `CommandExecution`(또는 `CommandExecutor`)로 위임. 인자 처리는 단순화 — 슬래시 명령 뒤 공백 이후를 raw 인자로 전달
- 기존 line-based 동작이 깨지지 않도록 `@Command`는 그대로 둠 (서브셋만 슬래시로 노출)

**완료 조건**: `ask`, `sessions`, `session new` 등이 popup에 자동 등장. 코드 변경으로 새 명령 추가 시 자동 노출.

**검증되는 미지수**: 2.

## 영향 범위

| 영역 | 변경 |
|---|---|
| `SecretaryApplication.kt` | (변경 없음 — runner는 별도 빈) |
| `application.yaml` | `spring.shell.interactive.enabled: false` 추가 |
| 신규 | `ChatTuiRunner.kt` (`ApplicationRunner`), `MessageView.kt` (custom BoxView), `SlashCommandCatalog.kt` |
| 기존 | `ChatCommands`/`SessionsTuiCommand`는 유지 (CommandRegistry로 조회되므로 자동 노출). 단 `ask`는 슬래시 흐름에서 의미가 모호해질 수 있으니 정리 필요 |
| `docs/` | `tui-ffm.md`에 native-access·provider 함정 추가, `docs/chat-tui.md` 신규 (구현 후) |

## 위험과 fallback

| 위험 | 가능성 | fallback |
|---|---|---|
| `InteractiveShellRunner` 우회 실패 | 중 | "/chat 명령으로 진입" 모드로 전환 — 셸은 그대로 두고 명령 안에서 TUI 띄우기 (이전 sessions 패턴) |
| `InputView`가 텍스트 변경 이벤트 없음 | 중 | KeyEvent 직접 구독 + 자체 StringBuilder. InputView 대신 BoxView에 cursor 직접 그리기 |
| 비동기 LLM 호출 + 화면 갱신 race | 중 | 호출 동안 InputView disable + 단일 스레드 직렬화. 최악의 경우 동기 호출(앱이 응답 동안 freeze) |
| `TerminalUI.setModal`이 floating popup 미지원 | 낮음 | GridView의 행을 동적으로 늘려 popup을 cell로 표시 |
| Spring Shell experimental TUI의 minor 업그레이드 시 시그니처 변경 | 낮음 | Spring Shell 버전을 카탈로그에 고정. CHANGELOG 모니터 |

## 테스트 방법

- 단위 테스트는 어려움 — TUI는 TTY 의존이라 mock 어려움. 대신 단계별 manual smoke test:
  - 단계 1: echo 동작
  - 단계 2: ChatMemory 멀티턴 (이름 알려준 뒤 다른 턴에서 묻기)
  - 단계 3: `/se` 후보 등장 + 선택 동작
  - 단계 4: 새 `@Command` 추가 시 popup에 자동 노출 (회귀 방지)
- 외부 터미널 — Ghostty / iTerm2 / Terminal.app 모두 OK. IDE Run 콘솔에서는 dumb fallback 발생 (이미 알려진 제약).

## 작업 순서 권장

1. 이 plan 사용자 검토 → 승인 시 단계 1 시작
2. 각 단계 완료 시 commit 분리 (`add : 채팅 TUI 단계 1 — 풀스크린 진입` 등)
3. 단계 4 완료 후 `docs/chat-tui.md` 정식 feature 문서 작성, 본 plan은 archive

## 참고

- 이전 학습된 함정 (`docs/tui-ffm.md`, `docs/long-term-memory.md`):
  - JLine FFM provider 강제(`org.jline.terminal.provider=ffm`)
  - JDK 25 native access enable (`Enable-Native-Access` manifest)
  - `BoxView.title`은 private field — `setTitle()` 직접 호출
  - `q` 단독 종료 키 (Ctrl-Q는 XOFF 충돌)
  - `ui.configure(view)` 호출 필수 (view 내부 키 핸들러 등록)
- Spring Shell TUI는 **experimental** — minor 업그레이드 시 시그니처 변경 가능
