package org.tianea.secretary.shell.command

import org.springframework.shell.core.command.annotation.Command
import org.springframework.shell.jline.tui.component.message.ShellMessageBuilder
import org.springframework.shell.jline.tui.component.view.TerminalUIBuilder
import org.springframework.shell.jline.tui.component.view.control.ListView
import org.springframework.shell.jline.tui.component.view.event.KeyEvent
import org.springframework.stereotype.Component
import org.tianea.secretary.core.session.SessionRepository
import org.tianea.secretary.core.session.SessionState
import java.io.PrintWriter
import java.io.StringWriter

@Component
class SessionsTuiCommand(
    private val uiBuilder: TerminalUIBuilder,
    private val sessions: SessionRepository,
    private val sessionState: SessionState,
) {
    @Command(
        name = ["sessions"],
        description = "Open a TUI list of all sessions and switch to the selected one (Enter=pick, q=cancel)",
    )
    fun sessions(): String =
        runCatching {
            val ids = sessions.listConversationIds()
            if (ids.isEmpty()) return@runCatching "No sessions yet. Run 'session new' first."

            val ui = uiBuilder.build()
            val list =
                ListView(ids, ListView.ItemStyle.NOCHECK)
                    .apply {
                        setTitle(" Sessions — Enter to switch, q to cancel ")
                        isShowBorder = true
                    }

            var picked: String? = null

            ui.apply {
                eventLoop
                    .viewEvents(ListView.ListViewOpenSelectedItemEvent::class.java, list)
                    .subscribe { event ->
                        @Suppress("UNCHECKED_CAST")
                        val args = event.args() as ListView.ListViewItemEventArgs<String>
                        picked = args.item()
                        ui.eventLoop.dispatch(ShellMessageBuilder.ofInterrupt())
                    }

                eventLoop.keyEvents().subscribe { e ->
                    if (e.plainKey == KeyEvent.Key.q) {
                        ui.eventLoop.dispatch(ShellMessageBuilder.ofInterrupt())
                    }
                }

                configure(list)
                setRoot(list, true)
                run()
            }

            picked?.let {
                sessionState.set(it)
                "Switched to session: $it"
            } ?: "Cancelled."
        }.getOrElse { e ->
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            "TUI error: ${e::class.qualifiedName}: ${e.message ?: "(no message)"}\n\n$sw"
        }
}
