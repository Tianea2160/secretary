---
paths:
  - "src/main/kotlin/org/tianea/secretary/shell/view/**"
  - "src/main/kotlin/org/tianea/secretary/shell/runner/**"
---

# Spring Shell TUI 작업 시 함정

Spring Shell TUI(`org.springframework.shell.jline.tui.*`)는 **experimental**. minor 업그레이드 시 시그니처 변경 가능.

## View 작성

- **`BoxView`는 frame만**. setText 같은 텍스트 컨테이너 API 없음. 메시지 누적은 `ListView<String>`로 시뮬레이션 (한 항목 = 한 줄).
- **`InputView`(기본)는 한글 cursor 어긋남 + `clear()` 미지원**. CJK 입력은 `WideCharInputView`(BoxView 상속, `AttributedString.columnLength`로 wcwidth 적용) 사용.
- **view 내부에서 dispatch는 `AbstractView.dispatch(Message<?>)` (protected)** 사용. `eventLoop?.dispatch`는 `ui.configure` 전후 null이라 silent no-op으로 빠진다. `InputView.done()`도 동일 패턴이 정답.
- **자체 `ViewEvent` 정의**: 인터페이스 구현, `view()`만 override (`args()`는 default). 발화는 `dispatch(ShellMessageBuilder.ofView(this, MyEvent(...)))`.
- **BoxView의 private field 직접 set 금지**: `view.title = "..."` 같은 Kotlin property 접근은 컴파일 에러 — `setTitle("...")` 직접 호출.

## Runner / Layout

- **종료 키는 `q` 단독**. `Ctrl-Q`는 일부 터미널에서 software flow control(XOFF/XON)과 충돌.
- **modal vs grid**: `TerminalUI.setModal(view)`는 풀스크린 overlay라 다른 view를 가린다. 분할 표시는 `GridView`를 동적으로 재구성(`clearItems` + `setRowSize` + `addItem`).
- **focus**: nested 컨테이너(예: `GridView > InputView`)에서는 `setRoot(grid, true)` 후 자식에 `setFocus(input)` 명시 필요. setRoot만으로는 root에만 focus.
- **`ui.configure(view)` 호출 필수** — view 내부 키 핸들러 등록을 트리거. 누락 시 화면은 그려지지만 키 입력 안 받음.

## 검증

- **TUI는 TTY 필수**. 외부 터미널(Ghostty/iTerm2/Terminal.app)에서만 동작. IDE Run 콘솔(`idea_rt.jar` 인자), `gradlew test`, pipe·redirect는 dumb terminal로 fallback.
- **검증 흐름**: `./gradlew bootJar` → 외부 터미널에서 `java -jar build/libs/secretary-0.0.1-SNAPSHOT.jar` + 별도 터미널에서 `tail -f logs/secretary.log`. 콘솔 로그는 alternate screen에 가려지므로 파일 로그가 단일 진실.

## 비동기 LLM 호출

- **`mono { agent.run(...) }`** (`kotlinx-coroutines-reactor`) 사용. `Mono.fromCallable { runBlocking { } }`은 boundedElastic 스레드를 블로킹하는 안티패턴.
- **Disposable 추적**: 새 호출 직전 이전 Disposable dispose, finally에서 dispose. 종료 시 백그라운드 토큰 누수 방지.
- **subscribe 콜백은 boundedElastic 스레드에서 실행**되므로 messages list 갱신은 `synchronized(messages)` 또는 `eventLoop.dispatch`로 직렬화.
