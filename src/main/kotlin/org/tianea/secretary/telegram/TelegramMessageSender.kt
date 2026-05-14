package org.tianea.secretary.telegram

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * Telegram outbound 메시지의 단일 진입점.
 *
 * [TelegramMarkdownRenderer]가 만든 MarkdownV2 텍스트를 4096자 제한 안에서 chunk로 잘라 전송한다.
 * 호출처는 chatId와 원본 텍스트만 알면 되고 escape/parse_mode/chunk 규칙은 여기로 응축된다.
 */
@Component
class TelegramMessageSender(
    private val telegramClient: TelegramClient,
    private val markdownRenderer: TelegramMarkdownRenderer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun send(
        chatId: Long,
        text: String,
    ) {
        if (text.isEmpty()) return
        markdownRenderer.render(text).chunked(TELEGRAM_TEXT_LIMIT).forEach { chunk ->
            runCatching {
                telegramClient.execute(
                    SendMessage
                        .builder()
                        .chatId(chatId)
                        .text(chunk)
                        .parseMode(PARSE_MODE)
                        .build(),
                )
            }.onFailure { log.error("send failed chatId={}", chatId, it) }
        }
    }

    private companion object {
        /** Telegram sendMessage text 상한은 4096자. UTF-16 surrogate 마진으로 4000 사용. */
        private const val TELEGRAM_TEXT_LIMIT = 4000
        private const val PARSE_MODE = "MarkdownV2"
    }
}
