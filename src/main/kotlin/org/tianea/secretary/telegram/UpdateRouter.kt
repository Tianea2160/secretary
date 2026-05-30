package org.tianea.secretary.telegram

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.objects.Update
import org.tianea.secretary.telegram.UpdateRouter.Companion.SHUTDOWN_GRACE_SECONDS

/**
 * 텔레그램 update 라우터.
 *
 * [LongPollingSingleThreadUpdateConsumer]의 디폴트 executor는 인터페이스의 static 필드라 JVM 전역에서 단일 스레드로 직렬화된다 →
 * 폴링 스레드가 LLM 호출에 막히면 다른 사용자 메시지도 같이 멈춘다. consume(Update)에서 사전 필터링한 뒤 가상 스레드 풀에 위임해 폴링 스레드를 즉시
 * 해제한다.
 *
 * 셧다운 시 in-flight 호출이 정리될 시간을 [SHUTDOWN_GRACE_SECONDS]초 만큼 기다린다.
 */
@Component
@ConditionalOnExpression($$"'${telegram.bot-token:}' != ''")
class UpdateRouter(private val telegramChatMessageService: TelegramChatMessageService) :
    LongPollingSingleThreadUpdateConsumer {
    override fun consume(update: Update) {
        val message = update.message ?: return

        telegramChatMessageService.addMessage(message)
    }

    private companion object {
        const val SHUTDOWN_GRACE_SECONDS = 10L
    }
}
