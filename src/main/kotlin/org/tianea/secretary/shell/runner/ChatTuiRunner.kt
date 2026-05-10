package org.tianea.secretary.shell.runner

import ai.koog.agents.core.agent.AIAgent
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.shell.jline.tui.component.message.ShellMessageBuilder
import org.springframework.shell.jline.tui.component.view.TerminalUI
import org.springframework.shell.jline.tui.component.view.TerminalUIBuilder
import org.springframework.shell.jline.tui.component.view.control.GridView
import org.springframework.shell.jline.tui.component.view.control.ListView
import org.springframework.shell.jline.tui.component.view.control.ViewDoneEvent
import org.springframework.stereotype.Component
import org.tianea.secretary.core.session.SessionState
import org.tianea.secretary.core.session.SlashCommand
import org.tianea.secretary.core.session.SlashCommandCatalog
import org.tianea.secretary.shell.view.WideCharInputView
import reactor.core.Disposable
import reactor.core.scheduler.Schedulers
import kotlin.system.exitProcess

/**
 * 풀스크린 채팅 TUI 진입점. Spring Shell의 InteractiveShellRunner는 default로 활성이지만
 * `@Order(HIGHEST_PRECEDENCE)`로 먼저 실행되고 finally에서 컨텍스트를 닫아 후속 ShellRunner가
 * 셸 prompt를 띄우지 못하게 한다.
 *
 * 화면: GridView 2~3분할 (chat / [popup] / input).
 * 입력: WideCharInputView — 한글 cursor + Enter 자동 clear.
 * Enter 분기: q·:quit 종료 / 슬래시 명령 → SlashCommandCatalog / 일반 입력 → AIAgent.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ChatTuiRunner(
    private val uiBuilder: TerminalUIBuilder,
    private val agent: AIAgent<String, String>,
    private val sessionState: SessionState,
    private val slashCatalog: SlashCommandCatalog,
    private val ctx: ConfigurableApplicationContext,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 진행 중 LLM 호출. quit 또는 새 호출 시 dispose해서 백그라운드 누수 방지. */
    private var pendingCall: Disposable? = null

    override fun run(args: ApplicationArguments) {
        log.info("ChatTuiRunner starting")
        try {
            startTui()
        } finally {
            log.info("ChatTuiRunner exiting")
            pendingCall?.dispose()
            // graceful: ApplicationContext close → bean destroy(DataSource 등) → JVM 종료.
            // exitProcess만으로는 다른 ShellRunner에 시간을 주거나 컨텍스트가 절반 살아있을 수 있다.
            exitProcess(SpringApplication.exit(ctx))
        }
    }

    private fun startTui() {
        val messages = mutableListOf("(채팅 시작 — Enter로 전송, 'q' 또는 ':quit'으로 종료)")

        val history =
            ListView(messages.toMutableList(), ListView.ItemStyle.NOCHECK).apply {
                setTitle(" Chat ")
                isShowBorder = true
            }

        val input =
            WideCharInputView().apply {
                setTitle(" Input ")
                isShowBorder = true
            }

        val popup =
            ListView(mutableListOf<SlashCommand>(), ListView.ItemStyle.NOCHECK).apply {
                setTitle(" Slash Commands ")
                isShowBorder = true
            }

        val grid = GridView()

        fun layoutGrid(showPopup: Boolean) {
            grid.clearItems()
            if (showPopup) {
                grid.setRowSize(0, 8, 3)
                grid.addItem(history, 0, 0, 1, 1, 0, 0)
                grid.addItem(popup, 1, 0, 1, 1, 0, 0)
                grid.addItem(input, 2, 0, 1, 1, 0, 0)
            } else {
                grid.setRowSize(0, 3)
                grid.addItem(history, 0, 0, 1, 1, 0, 0)
                grid.addItem(input, 1, 0, 1, 1, 0, 0)
            }
        }
        layoutGrid(showPopup = false)

        fun appendMessage(
            history: ListView<String>,
            msg: String,
        ) {
            synchronized(messages) {
                messages.add(msg)
                while (messages.size > MAX_MESSAGES) messages.removeAt(0)
                history.setItems(messages.toMutableList())
            }
        }

        fun replaceLastMessage(
            history: ListView<String>,
            msg: String,
        ) {
            synchronized(messages) {
                if (messages.isNotEmpty()) messages.removeAt(messages.lastIndex)
                messages.add(msg)
                history.setItems(messages.toMutableList())
            }
        }

        uiBuilder
            .build()
            .apply {
                eventLoop
                    .viewEvents(WideCharInputView.TextChangeEvent::class.java, input)
                    .subscribe { event ->
                        val text = event.newText
                        val show = text.startsWith("/")
                        if (show) {
                            val matches = slashCatalog.filter(text.substring(1))
                            popup.setItems(matches.toMutableList())
                            log.debug("Slash popup: query=\"{}\" matches={}", text, matches.size)
                        }
                        layoutGrid(show)
                        setFocus(input)
                        eventLoop.dispatch(ShellMessageBuilder.ofRedraw())
                    }

                eventLoop
                    .viewEvents(ViewDoneEvent::class.java, input)
                    .subscribe { _ ->
                        val text = input.inputText
                        log.debug("ViewDoneEvent received. inputText=\"{}\"", text)
                        input.clear()
                        layoutGrid(false)

                        when {
                            text == "q" || text == ":quit" -> {
                                log.info("Quit shortcut — dispatching interrupt")
                                eventLoop.dispatch(ShellMessageBuilder.ofInterrupt())
                            }

                            text.isBlank() -> {
                                eventLoop.dispatch(ShellMessageBuilder.ofRedraw())
                            }

                            text.startsWith("/") -> {
                                handleSlash(text, history, ::appendMessage)
                            }

                            else -> {
                                handleLlm(text, input, history, ::appendMessage, ::replaceLastMessage)
                            }
                        }
                    }

                listOf(grid, history, input, popup).forEach { configure(it) }
                setRoot(grid, true)
                setFocus(input)
                run()
            }
    }

    private fun TerminalUI.handleSlash(
        text: String,
        history: ListView<String>,
        appendMessage: (ListView<String>, String) -> Unit,
    ) {
        log.debug("Slash command: \"{}\"", text)
        appendMessage(history, "> $text")
        val result = slashCatalog.execute(text)
        if (result.quit) {
            log.info("Slash /quit — dispatching interrupt")
            eventLoop.dispatch(ShellMessageBuilder.ofInterrupt())
            return
        }
        result.messages.forEach { appendMessage(history, it) }
        eventLoop.dispatch(ShellMessageBuilder.ofRedraw())
    }

    private fun TerminalUI.handleLlm(
        text: String,
        input: WideCharInputView,
        history: ListView<String>,
        appendMessage: (ListView<String>, String) -> Unit,
        replaceLastMessage: (ListView<String>, String) -> Unit,
    ) {
        val sessionId = sessionState.currentOrNew()
        appendMessage(history, "> $text")
        appendMessage(history, "[thinking...]")
        input.enabled = false
        eventLoop.dispatch(ShellMessageBuilder.ofRedraw())

        log.debug("Calling agent.run sessionId={} input=\"{}\"", sessionId, text)
        // 이전 호출이 아직 진행 중이면 dispose. 사용자가 빠르게 두 번 입력해도 첫 호출이
        // 백그라운드에서 토큰 소모하지 않게.
        pendingCall?.dispose()
        pendingCall =
            mono { agent.run(text, sessionId) }
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                    { response ->
                        log.debug("agent.run response received len={}", response.length)
                        replaceLastMessage(history, response)
                        input.enabled = true
                        eventLoop.dispatch(ShellMessageBuilder.ofRedraw())
                    },
                    { error ->
                        log.error("agent.run failed", error)
                        replaceLastMessage(history, "[error] ${error.message}")
                        input.enabled = true
                        eventLoop.dispatch(ShellMessageBuilder.ofRedraw())
                    },
                )
    }

    companion object {
        /** 채팅 history 상한. 초과분은 head부터 trim. 장시간 세션의 메모리·redraw 비용 한계. */
        private const val MAX_MESSAGES = 500
    }
}