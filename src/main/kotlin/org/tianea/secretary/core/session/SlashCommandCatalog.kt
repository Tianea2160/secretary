package org.tianea.secretary.core.session

import org.springframework.stereotype.Component

data class SlashCommand(
    val name: String,
    val description: String,
    val execute: (args: String, chatId: Long) -> SlashCommandCatalog.Result,
) {
    override fun toString(): String = "/$name  $description"
}

/**
 * 슬래시 명령 카탈로그. 텔레그램 [org.tianea.secretary.telegram.UpdateRouter] 등 dispatch surface가 [execute]에 위임.
 * 새 명령 추가 시 [commands] 한 곳만 수정.
 */
@Component
class SlashCommandCatalog(private val sessions: SessionService) {
    private val commands: List<SlashCommand> =
        listOf(
            SlashCommand("sessions", "세션 목록") { _, _ ->
                val ids = sessions.listSessions()
                if (ids.isEmpty()) {
                    Result(listOf("[sessions] (no sessions yet)"))
                } else {
                    Result(listOf("[sessions] ${ids.size} found:") + ids.map { "  $it" })
                }
            }
        )

    fun all(): List<SlashCommand> = commands

    fun filter(query: String): List<SlashCommand> = commands.filter { it.name.startsWith(query) }

    fun execute(rawText: String, chatId: Long): Result {
        val parts = rawText.removePrefix("/").split(Regex("\\s+"), limit = 2)
        val name = parts.firstOrNull().orEmpty()
        val args = parts.getOrNull(1).orEmpty().trim()
        val cmd =
            commands.find { it.name == name }
                ?: return Result(listOf("[error] unknown command: /$name"))
        return cmd.execute(args, chatId)
    }

    data class Result(val messages: List<String>)
}
