package org.tianea.secretary.core.agent.graph

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatStrategyTest {
    @Test
    fun chatStrategyBuildsWithoutError() {
        assertEquals("secretary-chat", chatStrategy().name)
    }
}
