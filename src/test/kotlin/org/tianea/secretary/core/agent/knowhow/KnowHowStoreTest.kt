package org.tianea.secretary.core.agent.knowhow

import jakarta.persistence.EntityManager
import org.mockito.Mockito.mock
import org.springframework.ai.embedding.EmbeddingModel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnowHowStoreTest {
    private val store =
        KnowHowStore(
            mock(KnowHowRepository::class.java),
            mock(EmbeddingModel::class.java),
            mock(EntityManager::class.java),
        )

    private fun makeKnowHow(
        id: String = "test-id",
        importance: Double = 0.8,
        lastUsedAt: Instant? = null,
        createdAt: Instant = Instant.now(),
        body: String = "some procedure",
    ): KnowHow =
        KnowHow(
            id = id,
            chatId = 1L,
            intent = "test intent",
            body = body,
            importance = importance,
            useCount = 0,
            createdAt = createdAt,
            updatedAt = createdAt,
            lastUsedAt = lastUsedAt,
            sourceSessionId = "session-1",
        )

    @Test
    fun rerankAssignsHigherScoreToRecentItems() {
        val now = Instant.now()
        val old = makeKnowHow(id = "old", lastUsedAt = now.minusSeconds(7 * 24 * 3600L))
        val recent = makeKnowHow(id = "recent", lastUsedAt = now.minusSeconds(3600L))

        val candidates =
            listOf(
                ScoredKnowHow(knowHow = old, similarity = 0.9),
                ScoredKnowHow(knowHow = recent, similarity = 0.9),
            )

        val reranked = store.rerank(candidates)

        assertEquals("recent", reranked[0].knowHow.id)
        assertEquals("old", reranked[1].knowHow.id)
        assertTrue(reranked[0].rerankScore > reranked[1].rerankScore)
    }

    @Test
    fun rerankUsesCreatedAtWhenLastUsedAtIsNull() {
        val now = Instant.now()
        val oldCreated = makeKnowHow(id = "old-created", createdAt = now.minusSeconds(10 * 24 * 3600L), lastUsedAt = null)
        val newCreated = makeKnowHow(id = "new-created", createdAt = now.minusSeconds(3600L), lastUsedAt = null)

        val candidates =
            listOf(
                ScoredKnowHow(knowHow = oldCreated, similarity = 0.9),
                ScoredKnowHow(knowHow = newCreated, similarity = 0.9),
            )

        val reranked = store.rerank(candidates)

        assertEquals("new-created", reranked[0].knowHow.id)
        assertEquals("old-created", reranked[1].knowHow.id)
    }

    @Test
    fun rerankReturnsDescendingByScore() {
        val now = Instant.now()
        val candidates =
            listOf(
                ScoredKnowHow(knowHow = makeKnowHow(id = "a", importance = 0.9, lastUsedAt = now.minusSeconds(3600)), similarity = 0.8),
                ScoredKnowHow(knowHow = makeKnowHow(id = "b", importance = 0.5, lastUsedAt = now.minusSeconds(3600)), similarity = 0.8),
                ScoredKnowHow(knowHow = makeKnowHow(id = "c", importance = 0.7, lastUsedAt = now.minusSeconds(3600)), similarity = 0.8),
            )

        val reranked = store.rerank(candidates)

        assertTrue(reranked[0].rerankScore >= reranked[1].rerankScore)
        assertTrue(reranked[1].rerankScore >= reranked[2].rerankScore)
    }

    @Test
    fun applyTokenBudgetExcludesItemsOnceBudgetExhausted() {
        val candidates =
            listOf(
                ScoredKnowHow(knowHow = makeKnowHow(id = "a", body = "a".repeat(800)), similarity = 0.9),
                ScoredKnowHow(knowHow = makeKnowHow(id = "b", body = "b".repeat(800)), similarity = 0.8),
                ScoredKnowHow(knowHow = makeKnowHow(id = "c", body = "c".repeat(800)), similarity = 0.7),
            )

        val result = store.applyTokenBudget(candidates, tokenBudget = 200)

        assertEquals(1, result.size)
        assertEquals("a", result[0].knowHow.id)
    }

    @Test
    fun applyTokenBudgetAllowsMultipleItemsWhenBudgetSufficient() {
        val candidates =
            listOf(
                ScoredKnowHow(knowHow = makeKnowHow(id = "a", body = "x".repeat(400)), similarity = 0.9),
                ScoredKnowHow(knowHow = makeKnowHow(id = "b", body = "y".repeat(400)), similarity = 0.8),
                ScoredKnowHow(knowHow = makeKnowHow(id = "c", body = "z".repeat(400)), similarity = 0.7),
            )

        val result = store.applyTokenBudget(candidates, tokenBudget = 200)

        assertEquals(2, result.size)
        assertEquals("a", result[0].knowHow.id)
        assertEquals("b", result[1].knowHow.id)
    }

    @Test
    fun applyTokenBudgetReturnsEmptyListWhenBudgetIsZero() {
        val candidates =
            listOf(
                ScoredKnowHow(knowHow = makeKnowHow(id = "a", body = "some text"), similarity = 0.9),
            )

        val result = store.applyTokenBudget(candidates, tokenBudget = 0)

        assertTrue(result.isEmpty())
    }

    @Test
    fun applyTokenBudgetReturnsAllItemsWhenBudgetIsLarge() {
        val candidates =
            listOf(
                ScoredKnowHow(knowHow = makeKnowHow(id = "a", body = "short"), similarity = 0.9),
                ScoredKnowHow(knowHow = makeKnowHow(id = "b", body = "also short"), similarity = 0.8),
            )

        val result = store.applyTokenBudget(candidates, tokenBudget = 10000)

        assertEquals(2, result.size)
    }
}
