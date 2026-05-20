package org.tianea.secretary.core.agent.graph

import org.mockito.Mockito.mock
import org.tianea.secretary.telegram.TelegramReactionSender
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatStrategyTest {
    @Test
    fun chatStrategyBuildsWithoutError() {
        val strategy = ChatStrategyConfig().chatStrategy(mock(TelegramReactionSender::class.java))
        assertEquals("secretary-chat", strategy.name)
    }
}
