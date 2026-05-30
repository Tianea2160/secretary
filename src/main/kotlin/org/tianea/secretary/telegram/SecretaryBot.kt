package org.tianea.secretary.telegram

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot

/**
 * `telegrambots-springboot-longpolling-starter`가 [SpringLongPollingBot] 빈을 발견해 long polling을 시작.
 */
@Component
@ConditionalOnExpression($$"'${telegram.bot-token:}' != ''")
class SecretaryBot(private val props: TelegramProperties, private val router: UpdateRouter) :
    SpringLongPollingBot {
    override fun getBotToken(): String = props.botToken

    override fun getUpdatesConsumer(): LongPollingUpdateConsumer = router
}
