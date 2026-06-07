package org.tianea.secretary.core.agent.knowhow

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito.mock
import org.springframework.ai.embedding.EmbeddingModel

/**
 * [KnowHowConsolidator]의 ADD/UPDATE/SKIP 분기 검증.
 *
 * [PromptExecutor]는 고정 JSON을 반환하는 구체 stub으로 대체하고, [KnowHowStore]는 테스트 전용 stub으로 대체한다. Mockito의
 * `verify` 대신 stub의 호출 기록을 직접 확인한다 — `KnowHowStore`가 구체 클래스이고 Mockito+Kotlin의 non-null 파라미터 조합이
 * `capture()`/`eq()` 반환 null로 NPE를 일으키기 때문이다.
 */
class KnowHowConsolidatorTest {
    /**
     * [PromptExecutor] 추상 클래스를 고정 JSON 응답으로 대체하는 테스트 전용 stub.
     *
     * [responseJson]을 [Message.Assistant]로 그대로 반환한다. [executeStructured]가 내부적으로 [execute]를 호출해
     * JSON을 파싱하므로 이 stub 하나로 구조화 응답 경로 전체를 교체할 수 있다.
     */
    private class FixedJsonPromptExecutor(private val responseJson: String) : PromptExecutor() {
        override suspend fun execute(
            prompt: Prompt,
            model: ai.koog.prompt.llm.LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant =
            Message.Assistant(content = responseJson, metaInfo = ResponseMetaInfo.Empty)

        override fun executeStreaming(
            prompt: Prompt,
            model: ai.koog.prompt.llm.LLModel,
            tools: List<ToolDescriptor>,
        ): Flow<StreamFrame> = emptyFlow()

        override suspend fun moderate(
            prompt: Prompt,
            model: ai.koog.prompt.llm.LLModel,
        ): ModerationResult = ModerationResult(isHarmful = false, categories = emptyMap())

        override suspend fun models(): List<ai.koog.prompt.llm.LLModel> = emptyList()

        override fun close() = Unit
    }

    /**
     * [KnowHowStore]의 테스트 전용 stub.
     *
     * [save]/[update] 호출 여부와 인자를 프로퍼티에 기록해 검증에 사용한다. [searchSimilar] 결과는 [searchResult]로 사전에 설정한다.
     */
    private class SpyKnowHowStore :
        KnowHowStore(
            mock(KnowHowRepository::class.java),
            mock(EmbeddingModel::class.java),
            mock(EntityManager::class.java),
            KnowHowProperties(),
        ) {
        var searchResult: List<ScoredKnowHow> = emptyList()
        var savedKnowHow: KnowHow? = null
        var updatedId: String? = null
        var updatedIntent: String? = null
        var updatedBody: String? = null

        override fun searchSimilar(chatId: Long, intent: String, topK: Int): List<ScoredKnowHow> =
            searchResult

        override fun save(knowHow: KnowHow, embeddingText: String): String {
            savedKnowHow = knowHow
            return knowHow.id
        }

        override fun update(
            id: String,
            intent: String,
            body: String,
            importance: Double,
            embeddingText: String,
        ) {
            updatedId = id
            updatedIntent = intent
            updatedBody = body
        }
    }

    private val model = LLModel(provider = LLMProvider.Ollama, id = "test-model")

    private val candidate = KnowHowCandidate(intent = "테스트 의도", body = "테스트 본문", importance = 0.8)

    private fun makeSimilarKnowHow(id: String = "existing-id"): ScoredKnowHow =
        ScoredKnowHow(
            knowHow =
                KnowHow(
                    id = id,
                    chatId = 1L,
                    intent = "기존 의도",
                    body = "기존 본문",
                    importance = 0.7,
                    useCount = 2,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                    lastUsedAt = null,
                    sourceSessionId = "session-0",
                ),
            similarity = 0.9,
        )

    @Test
    fun consolidateCallsSaveWhenVerdictIsAdd() = runBlocking {
        val executor = FixedJsonPromptExecutor("""{"action":"ADD"}""")
        val store = SpyKnowHowStore()
        store.searchResult = listOf(makeSimilarKnowHow())

        val consolidator = KnowHowConsolidator(executor, model, store, jacksonObjectMapper())
        consolidator.consolidate(listOf(candidate), chatId = 1L, sourceSessionId = "session-1")

        val saved = store.savedKnowHow
        assertTrue(saved != null, "save() should have been called")
        assertEquals(candidate.intent, saved.intent)
        assertEquals(candidate.body, saved.body)
        assertFalse(store.updatedId != null, "update() should not have been called")
    }

    @Test
    fun consolidateCallsUpdateWhenVerdictIsUpdate() = runBlocking {
        val existingId = "existing-id"
        val executor =
            FixedJsonPromptExecutor(
                """{"action":"UPDATE","targetId":"$existingId","mergedIntent":"통합 의도","mergedBody":"통합 본문"}"""
            )
        val store = SpyKnowHowStore()
        store.searchResult = listOf(makeSimilarKnowHow(existingId))

        val consolidator = KnowHowConsolidator(executor, model, store, jacksonObjectMapper())
        consolidator.consolidate(listOf(candidate), chatId = 1L, sourceSessionId = "session-1")

        assertEquals(existingId, store.updatedId)
        assertEquals("통합 의도", store.updatedIntent)
        assertEquals("통합 본문", store.updatedBody)
        assertFalse(store.savedKnowHow != null, "save() should not have been called")
    }

    @Test
    fun consolidateCallsNeitherSaveNorUpdateWhenVerdictIsSkip() = runBlocking {
        val executor = FixedJsonPromptExecutor("""{"action":"SKIP"}""")
        val store = SpyKnowHowStore()
        store.searchResult = listOf(makeSimilarKnowHow())

        val consolidator = KnowHowConsolidator(executor, model, store, jacksonObjectMapper())
        consolidator.consolidate(listOf(candidate), chatId = 1L, sourceSessionId = "session-1")

        assertFalse(store.savedKnowHow != null, "save() should not have been called for SKIP")
        assertFalse(store.updatedId != null, "update() should not have been called for SKIP")
    }

    @Test
    fun consolidateCallsSaveDirectlyWhenNoSimilarExists() = runBlocking {
        val executor = FixedJsonPromptExecutor("""{"action":"ADD"}""")
        val store = SpyKnowHowStore()
        store.searchResult = emptyList()

        val consolidator = KnowHowConsolidator(executor, model, store, jacksonObjectMapper())
        consolidator.consolidate(listOf(candidate), chatId = 1L, sourceSessionId = "session-1")

        val saved = store.savedKnowHow
        assertTrue(saved != null, "save() should have been called when no similar exists")
        assertEquals(candidate.intent, saved.intent)
    }
}
