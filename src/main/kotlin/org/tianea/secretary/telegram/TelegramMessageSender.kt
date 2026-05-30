package org.tianea.secretary.telegram

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * Telegram outbound 메시지의 단일 진입점.
 *
 * 송신 텍스트는 [LatexUnicodeRenderer] → [TelegramMarkdownRenderer] 파이프라인을 거쳐 `$...$` 수식이 유니코드로 평탄화된 뒤
 * MarkdownV2로 직렬화되고, 4096자 제한 안에서 chunk로 잘려 전송된다. 호출처는 chatId와 원본 텍스트만 알면 되고
 * escape/parse_mode/chunk 규칙은 여기로 응축된다.
 */
@Component
class TelegramMessageSender(
    private val telegramClient: TelegramClient,
    private val latexUnicodeRenderer: TelegramRenderer,
    private val telegramMarkdownRenderer: TelegramRenderer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun send(chatId: Long, text: String) {
        if (text.isEmpty()) return

        text
            .let(latexUnicodeRenderer::render)
            .let(telegramMarkdownRenderer::render)
            .chunked(TELEGRAM_TEXT_LIMIT)
            .forEach { chunk -> doSend(chatId, chunk) }
    }

    private fun doSend(chatId: Long, chunk: String) =
        runCatching {
                telegramClient.execute(
                    SendMessage.builder().chatId(chatId).text(chunk).parseMode(PARSE_MODE).build()
                )
            }
            .onFailure { log.error("send failed chatId={}", chatId, it) }

    private companion object {
        /** Telegram sendMessage text 상한은 4096자. UTF-16 surrogate 마진으로 4000 사용. */
        private const val TELEGRAM_TEXT_LIMIT = 4000
        private const val PARSE_MODE = "MarkdownV2"
    }
}
