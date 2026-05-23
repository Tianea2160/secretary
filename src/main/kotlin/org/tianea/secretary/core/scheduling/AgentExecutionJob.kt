package org.tianea.secretary.core.scheduling

import org.quartz.DisallowConcurrentExecution
import org.quartz.JobExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.quartz.QuartzJobBean
import org.springframework.stereotype.Component
import org.tianea.secretary.core.agent.AssistantRunner
import org.tianea.secretary.core.session.SessionService
import org.tianea.secretary.core.session.SlashCommandCatalog
import org.tianea.secretary.telegram.TelegramChatMessageService
import org.tianea.secretary.telegram.TelegramMessageSender

/**
 * 텔레그램 메시지 큐를 폴링해 슬래시 커맨드 또는 AI Agent로 라우팅한다.
 *
 * `@DisallowConcurrentExecution` — 동일 `JobDetail`의 동시 실행을 차단한다. 1초 cron 주기에서
 * 직전 실행이 1초보다 오래 걸리면 다음 트리거가 스킵되어 중복 처리를 막는다.
 *
 * @see AgentExecutionJobConfig `JobDetail`/`Trigger` 빈 등록 위치
 */
@Component
@DisallowConcurrentExecution
class AgentExecutionJob(
    private val telegramChatMessageService: TelegramChatMessageService,
    private val slashCommandCatalog: SlashCommandCatalog,
    private val sessionService: SessionService,
    private val assistantRunner: AssistantRunner,
    private val messageSender: TelegramMessageSender,
) : QuartzJobBean() {
    private val log: Logger = LoggerFactory.getLogger(javaClass)

    override fun executeInternal(context: JobExecutionContext) {
        if (telegramChatMessageService.isEmpty()) return

        val message = telegramChatMessageService.popMessage()
        val text = message.text ?: return
        val messageId = message.messageId
        val chatId = message.chatId ?: return

        runCatching { handle(chatId, text, messageId) }
            .onFailure { log.error("update handling failed chat_id={}", chatId, it) }
    }

    private fun handle(
        chatId: Long,
        text: String,
        messageId: Int,
    ) {
        log.debug("Incoming chat_id={} text=\"{}\"", chatId, text)
        val replies: List<String> =
            if (text.startsWith("/")) {
                slashCommandCatalog.execute(text, chatId).messages
            } else {
                val sessionId = sessionService.currentOrNew(chatId)
                listOf(assistantRunner.run(chatId, sessionId, text, messageId))
            }

        replies.forEach { messageSender.send(chatId, it) }
    }
}
