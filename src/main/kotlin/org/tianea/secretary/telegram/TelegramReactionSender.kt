package org.tianea.secretary.telegram

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.api.methods.reactions.SetMessageReaction
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionType
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionTypeEmoji
import org.telegram.telegrambots.meta.generics.TelegramClient
import org.tianea.secretary.telegram.TelegramReactionSender.Companion.PROCESSING_EMOJI

/**
 * LLM이 어느 대화를 처리 중인지 사용자에게 가시화하는 Telegram 메시지 리액션 송신자.
 *
 * agent 실행 시작 시 사용자 메시지에 [PROCESSING_EMOJI] 리액션을 달고, 종료 시(정상·예외 무관) 제거한다. 리액션 호출 실패는 agent 실행을 막지
 * 않도록 삼킨다 — 메시지가 너무 오래됐거나 봇 권한이 없으면 Telegram이 거부할 수 있으나, 처리중 표식은 부가 기능이라 본 흐름을 중단시킬 이유가 없다.
 */
@Component
class TelegramReactionSender(private val telegramClient: TelegramClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun setProcessing(chatId: Long, messageId: Int) =
        react(
            chatId,
            messageId,
            listOf(ReactionTypeEmoji.builder().emoji(PROCESSING_EMOJI).build()),
        )

    fun clearProcessing(chatId: Long, messageId: Int) = react(chatId, messageId, emptyList())

    private fun react(chatId: Long, messageId: Int, reactions: List<ReactionType>) {
        runCatching {
                telegramClient.execute(
                    SetMessageReaction.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .reactionTypes(reactions)
                        .build()
                )
            }
            .onFailure { log.warn("reaction failed chatId={} messageId={}", chatId, messageId, it) }
    }

    private companion object {
        /** Telegram이 고정 허용하는 리액션 이모지 셋에 포함된 "eyes". */
        private const val PROCESSING_EMOJI = "👀"
    }
}
