package org.tianea.secretary.shell

import org.jline.utils.AttributedString
import org.slf4j.LoggerFactory
import org.springframework.shell.jline.tui.component.message.ShellMessageBuilder
import org.springframework.shell.jline.tui.component.view.control.BoxView
import org.springframework.shell.jline.tui.component.view.control.View
import org.springframework.shell.jline.tui.component.view.control.ViewDoneEvent
import org.springframework.shell.jline.tui.component.view.control.ViewEvent
import org.springframework.shell.jline.tui.component.view.event.KeyEvent
import org.springframework.shell.jline.tui.component.view.event.KeyHandler
import org.springframework.shell.jline.tui.component.view.screen.Screen
import org.springframework.shell.jline.tui.geom.Position

/**
 * Spring Shell TUI 기본 InputView를 wide char(CJK) 지원 + Enter 자동 clear로 대체한 구현.
 *
 * 기본 InputView의 한계:
 * - cursor 위치를 char count 기반으로 계산 → 한글(wcwidth=2) 입력 시 cursor가 글자보다
 *   1 cell씩 뒤처짐
 * - public clear API 없음 → Enter 후 입력창에 텍스트가 남아 다음 입력에 누적
 *
 * 본 구현은:
 * - 텍스트를 StringBuilder로 직접 관리 (코드포인트 인덱스 = char index, 한글은 BMP라 1 char)
 * - cursor 화면 위치 = 0..cursorIndex의 wcwidth 합 (JLine WCWidth 사용)
 * - Enter 시 ViewDoneEvent emit → 자동 clear()
 * - clear() public 노출
 */
class WideCharInputView : BoxView() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val buffer = StringBuilder()
    private var cursorIndex = 0

    /** LLM 비동기 호출 중 입력 잠금. false면 모든 키 무시. */
    var enabled: Boolean = true
        set(value) {
            field = value
            log.debug("Input enabled={}", value)
        }

    val inputText: String
        get() = buffer.toString()

    fun clear() {
        buffer.setLength(0)
        cursorIndex = 0
    }

    override fun getKeyHandler(): KeyHandler =
        KeyHandler { args ->
            val event = args.event()
            if (!enabled) {
                log.debug("Input disabled — ignoring key={}", event.key())
            } else {
                log.debug("KeyEvent key={} data={} hasCtrl={}", event.key(), event.data(), event.hasCtrl())
                handleKey(event)
            }
            // 항상 consumed=true. 인식 못한 키도 부모 GridView로 전파되어 사라지지 않게.
            KeyHandler.resultOf(event, true, this)
        }

    private fun handleKey(event: KeyEvent) {
        val before = buffer.toString()
        when {
            event.isKey(KeyEvent.Key.Enter) -> {
                // AbstractView.dispatch(Message) 사용. eventLoop 직접 접근은 ui.configure 전후
                // null 가능성이 있어 dispatch가 no-op으로 빠질 수 있음. InputView.done()도 동일 패턴.
                // clear()는 subscribe 콜백에서 호출 — 여기서 비우면 콜백이 inputText를 읽을 때
                // 이미 빈 문자열이 되어 메시지가 누락된다.
                log.debug("Enter — dispatching ViewDoneEvent. inputText=\"{}\"", buffer.toString())
                dispatch(ShellMessageBuilder.ofView(this, ViewDoneEvent.of(this)))
                return
            }

            event.isKey(KeyEvent.Key.Backspace) -> {
                if (cursorIndex > 0) {
                    buffer.deleteCharAt(cursorIndex - 1)
                    cursorIndex--
                }
            }

            event.isKey(KeyEvent.Key.Delete) -> {
                if (cursorIndex < buffer.length) {
                    buffer.deleteCharAt(cursorIndex)
                }
            }

            event.isKey(KeyEvent.Key.CursorLeft) -> {
                if (cursorIndex > 0) cursorIndex--
            }

            event.isKey(KeyEvent.Key.CursorRight) -> {
                if (cursorIndex < buffer.length) cursorIndex++
            }

            else -> {
                // Printable 또는 Unicode. data() 우선, 없으면 key()를 char로 변환.
                val data = event.data()
                val text =
                    when {
                        !data.isNullOrEmpty() -> data
                        event.key() in 32..0x10FFFF -> Character.toString(event.key())
                        else -> return
                    }
                buffer.insert(cursorIndex, text)
                cursorIndex += text.length
            }
        }
        val after = buffer.toString()
        if (before != after) {
            // 텍스트 변경 이벤트 emit. 슬래시 명령 popup 필터링 등 외부 구독자가 사용.
            dispatch(ShellMessageBuilder.ofView(this, TextChangeEvent(this, before, after)))
        }
    }

    class TextChangeEvent(
        private val src: WideCharInputView,
        val oldText: String,
        val newText: String,
    ) : ViewEvent {
        override fun view(): View = src
    }

    override fun drawInternal(screen: Screen) {
        super.drawInternal(screen)
        val rect = rect
        val pad = if (isShowBorder) 1 else 0
        val innerX = rect.x() + pad
        val innerY = rect.y() + pad

        screen
            .writerBuilder()
            .build()
            .text(buffer.toString(), innerX, innerY)

        if (hasFocus()) {
            // JLine AttributedString.columnLength()이 wcwidth + surrogate pair 처리를 모두 담당.
            // 자체 loop를 돌리는 대신 표준 호출로 위임. 단 control char는 buffer에 못 들어오게
            // KeyHandler가 사전 필터링하므로 음수 wcwidth 케이스는 발생 안 함.
            val cellOffset = AttributedString(buffer.substring(0, cursorIndex)).columnLength()
            screen.isShowCursor = true
            screen.cursorPosition = Position(innerX + cellOffset, innerY)
        }
    }
}
