package org.tianea.secretary.telegram

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 텔레그램 봇 설정.
 *
 * @property botToken 빈 문자열이면 봇 빈 자체가 등록되지 않음 — 토큰 없는 환경에서도 컨텍스트 로딩 통과.
 * @property allowedChatIds 응답할 chat_id 화이트리스트. 미허용 chat은 로그만 남기고 무시.
 */
@ConfigurationProperties(prefix = "telegram")
data class TelegramProperties(
    val botToken: String = "",
    val allowedChatIds: Set<Long> = emptySet(),
)
