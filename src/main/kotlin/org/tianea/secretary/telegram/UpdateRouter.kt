package org.tianea.secretary.telegram

import ai.koog.agents.core.agent.AIAgent
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.generics.TelegramClient
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
    private val agent: AIAgent<String, String>,
    private val telegramClient: TelegramClient,
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
        val text = update.message?.text ?: return
        workers.execute {
            runCatching { handle(chatId, text) }
                .onFailure { log.error("update handling failed chat_id={}", chatId, it) }
        }
    }

    private fun handle(
        chatId: Long,
        text: String,
    ) {
        log.debug("Incoming chat_id={} text=\"{}\"", chatId, text)
        val replies: List<String> =
            if (text.startsWith("/")) {
                slashCatalog.execute(text, chatId).messages
            } else {
                val sessionId = sessionService.currentOrNew(chatId)
                listOf(runBlocking { agent.run(text, sessionId) })
            }
        replies.forEach { send(chatId, it) }
    }

    private fun send(
        chatId: Long,
        text: String,
    ) {
        if (text.isEmpty()) return
        text.chunked(TELEGRAM_TEXT_LIMIT).forEach { chunk ->
            runCatching {
                telegramClient.execute(
                    SendMessage
                        .builder()
                        .chatId(chatId)
                        .text(chunk)
                        .build(),
                )
            }.onFailure { log.error("send failed chatId={}", chatId, it) }
        }
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
        /** Telegram sendMessage text 상한은 4096자. UTF-16 surrogate 마진으로 4000 사용. */
        private const val TELEGRAM_TEXT_LIMIT = 4000

        /** in-flight LLM 호출이 끝날 시간을 부여. 넘어가면 강제 종료. */
        private const val SHUTDOWN_TIMEOUT_SECONDS = 10L
    }
}
