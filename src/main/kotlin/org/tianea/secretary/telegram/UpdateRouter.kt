package org.tianea.secretary.telegram

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.objects.Update
import org.tianea.secretary.core.agent.AssistantRunner
import org.tianea.secretary.core.session.SessionService
import org.tianea.secretary.core.session.SlashCommandCatalog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 텔레그램 update 라우터.
 *
 * [LongPollingSingleThreadUpdateConsumer]의 디폴트 executor는 인터페이스의 static 필드라 JVM 전역에서
 * 단일 스레드로 직렬화된다 → 폴링 스레드가 LLM 호출에 막히면 다른 사용자 메시지도 같이 멈춘다.
 * consume(Update)에서 사전 필터링한 뒤 가상 스레드 풀에 위임해 폴링 스레드를 해제한다.
 */
@Component
@ConditionalOnExpression($$"'${telegram.bot-token:}' != ''")
class UpdateRouter(
    private val sessionService: SessionService,
    private val slashCatalog: SlashCommandCatalog,
    private val assistantRunner: AssistantRunner,
    private val messageSender: TelegramMessageSender,
    private val props: TelegramProperties,
) : LongPollingSingleThreadUpdateConsumer {
    private val log = LoggerFactory.getLogger(javaClass)

    private val workers: ExecutorService =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("tg-worker-", 0).factory(),
        )

    override fun consume(update: Update) {
        val chatId = extractChatId(update) ?: return
        if (chatId !in props.allowedChatIds) {
            log.warn("Rejected chat_id={} (not in allowlist)", chatId)
            return
        }
        val message = update.message ?: return
        val text = message.text ?: return
        workers.execute {
            runCatching { handle(chatId, text, message.messageId) }
                .onFailure { log.error("update handling failed chat_id={}", chatId, it) }
        }
    }

    private fun handle(
        chatId: Long,
        text: String,
        messageId: Int,
    ) {
        log.debug("Incoming chat_id={} text=\"{}\"", chatId, text)
        val replies: List<String> =
            if (text.startsWith("/")) {
                slashCatalog.execute(text, chatId).messages
            } else {
                val sessionId = sessionService.currentOrNew(chatId)
                listOf(assistantRunner.run(chatId, sessionId, text, messageId))
            }
        replies.forEach { messageSender.send(chatId, it) }
    }

    @PreDestroy
    fun shutdown() {
        workers.shutdown()
        if (!workers.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            workers.shutdownNow()
        }
    }

    private fun extractChatId(u: Update): Long? =
        u.message?.chat?.id
            ?: u.editedMessage?.chat?.id
            ?: u.channelPost?.chat?.id
            ?: u.editedChannelPost?.chat?.id
            ?: u.callbackQuery
                ?.message
                ?.chat
                ?.id
            ?: u.myChatMember?.chat?.id
            ?: u.chatMember?.chat?.id
            ?: u.chatJoinRequest?.chat?.id

    companion object {
        /** in-flight LLM 호출이 끝날 시간을 부여. 넘어가면 강제 종료. */
        private const val SHUTDOWN_TIMEOUT_SECONDS = 10L
    }
}
