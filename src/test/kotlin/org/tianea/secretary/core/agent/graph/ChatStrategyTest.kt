package org.tianea.secretary.core.agent.graph

import jakarta.persistence.EntityManager
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito.mock
import org.springframework.ai.embedding.EmbeddingModel
import org.tianea.secretary.core.agent.knowhow.KnowHowConsolidator
import org.tianea.secretary.core.agent.knowhow.KnowHowProperties
import org.tianea.secretary.core.agent.knowhow.KnowHowReflector
import org.tianea.secretary.core.agent.knowhow.KnowHowRepository
import org.tianea.secretary.core.agent.knowhow.KnowHowStore
import org.tianea.secretary.telegram.TelegramReactionSender

class ChatStrategyTest {
    private val reactionSender = mock(TelegramReactionSender::class.java)
    private val knowHowStore =
        KnowHowStore(
            mock(KnowHowRepository::class.java),
            mock(EmbeddingModel::class.java),
            mock(EntityManager::class.java),
            KnowHowProperties(),
        )
    private val knowHowReflector = mock(KnowHowReflector::class.java)
    private val knowHowConsolidator = mock(KnowHowConsolidator::class.java)

    @Test
    fun chatStrategyBuildsWithoutError() {
        val strategy =
            ChatStrategyConfig()
                .chatStrategy(
                    reactionSender = reactionSender,
                    knowHowStore = knowHowStore,
                    knowHowReflector = knowHowReflector,
                    knowHowConsolidator = knowHowConsolidator,
                    knowHowProperties = KnowHowProperties(),
                )
        assertEquals("secretary-chat", strategy.name)
    }

    @Test
    fun chatStrategyBuildsWithKnowHowDisabled() {
        val strategy =
            ChatStrategyConfig()
                .chatStrategy(
                    reactionSender = reactionSender,
                    knowHowStore = knowHowStore,
                    knowHowReflector = knowHowReflector,
                    knowHowConsolidator = knowHowConsolidator,
                    knowHowProperties = KnowHowProperties(enabled = false),
                )
        assertEquals("secretary-chat", strategy.name)
    }
}
