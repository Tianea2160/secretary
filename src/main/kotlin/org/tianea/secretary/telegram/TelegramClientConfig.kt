package org.tianea.secretary.telegram

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.generics.TelegramClient

/** 봇 메시지 송신 클라이언트. `telegram.bot-token`이 비어 있으면 빈 등록을 건너뛰어 토큰 없는 환경에서도 컨텍스트가 뜬다. */
@Configuration
@ConditionalOnExpression($$"'${telegram.bot-token:}' != ''")
class TelegramClientConfig {
    @Bean
    fun telegramClient(props: TelegramProperties): TelegramClient =
        OkHttpTelegramClient(props.botToken)
}
