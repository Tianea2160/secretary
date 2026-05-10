package org.tianea.secretary.core.session

import org.springframework.stereotype.Component

/**
 * 슬래시 명령 한 항목. 표시 형식(toString)과 실행 핸들러를 함께 담는다.
 * 새 명령 추가 시 한 곳(commands list)만 수정하면 끝 — when 분기 별도 동기화 불필요.
 */
data class SlashCommand(
    val name: String,
    val description: String,
    val execute: (args: String) -> SlashCommandCatalog.Result,
) {
    override fun toString(): String = "/$name  $description"
}

/**
 * 슬래시 명령 카탈로그. popup 표시용 정보 + 실제 실행 로직을 단일 list로 관리.
 * 세션 관련 동작은 SessionService에 위임 — ChatCommands(line-mode)와 단일 출처 공유.
 */
@Component
class SlashCommandCatalog(
    private val sessions: SessionService,
) {
    private val commands: List<SlashCommand> =
        listOf(
            SlashCommand("sessions", "세션 목록") { _ ->
                val ids = sessions.listSessions()
                if (ids.isEmpty()) {
                    Result(listOf("[sessions] (no sessions yet)"))
                } else {
                    Result(listOf("[sessions] ${ids.size} found:") + ids.map { "  $it" })
                }
            },
            SlashCommand("session-new", "새 세션 시작") { _ ->
                Result(listOf("[session new] ${sessions.newSession()}"))
            },
            SlashCommand("session-current", "현재 세션 표시") { _ ->
                Result(listOf("[session] ${sessions.currentSession() ?: "(no active session)"}"))
            },
            SlashCommand("session-use", "세션 전환 (id 인자)") { args ->
                if (args.isBlank()) {
                    Result(listOf("[error] usage: /session-use <id>"))
                } else {
                    sessions.useSession(args)
                    Result(listOf("[session use] $args"))
                }
            },
            SlashCommand("quit", "종료") { _ -> Result(emptyList(), quit = true) },
        )

    fun all(): List<SlashCommand> = commands

    /** 입력 문자열(/ 제외)로 prefix 필터링. */
    fun filter(query: String): List<SlashCommand> = commands.filter { it.name.startsWith(query) }

    /** "/cmd args..." 한 줄을 파싱해 매칭되는 명령에 위임. */
    fun execute(rawText: String): Result {
        val parts = rawText.removePrefix("/").split(Regex("\\s+"), limit = 2)
        val name = parts.firstOrNull().orEmpty()
        val args = parts.getOrNull(1).orEmpty().trim()
        val cmd = commands.find { it.name == name } ?: return Result(listOf("[error] unknown command: /$name"))
        return cmd.execute(args)
    }

    /** execute() 결과. messages는 채팅에 append할 줄들, quit는 앱 종료 신호. */
    data class Result(
        val messages: List<String>,
        val quit: Boolean = false,
    )
}
