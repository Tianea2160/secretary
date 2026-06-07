package org.tianea.secretary.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `dataset.yaml`이 [EvalItem] 스키마로 깨지지 않고 로드되고 구조 불변식을 지키는지 라이브 의존성 없이 검증한다.
 *
 * 회귀 게이트([RegressionEvalTest])는 opt-in 라이브 테스트라, YAML이 깨지면 Ollama/Langfuse가 떠 있을 때만 뒤늦게 드러난다. 이
 * 오프라인 테스트가 스키마-데이터 정합성을 매 빌드에서 잡는다.
 */
class EvalDatasetTest {
    private val dataset = EvalDatasetLoader.load()

    @Test
    fun `dataset loads with items`() {
        assertTrue(dataset.items.isNotEmpty(), "dataset.yaml must contain items")
    }

    @Test
    fun `every item has a stable id and a non-blank reference answer`() {
        val ids = dataset.items.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "item ids must be unique")
        for (item in dataset.items) {
            assertFalse(item.id.isBlank(), "item id must not be blank")
            assertFalse(
                item.referenceAnswer.isBlank(),
                "item '${item.id}' must have a reference answer",
            )
        }
    }

    @Test
    fun `resolvedTurns always yields at least one turn with input`() {
        for (item in dataset.items) {
            val turns = item.resolvedTurns()
            assertTrue(turns.isNotEmpty(), "item '${item.id}' resolved to no turns")
            for (turn in turns) {
                assertFalse(turn.input.isBlank(), "item '${item.id}' has a turn with blank input")
            }
        }
    }

    @Test
    fun `know-how scenarios exercise the configured ability cohorts`() {
        val abilityTags =
            setOf("info-extraction", "multi-session", "temporal", "knowledge-update", "abstention")
        val covered = dataset.items.flatMap { it.tags }.toSet().intersect(abilityTags)
        assertEquals(
            abilityTags,
            covered,
            "every ability cohort must have at least one scenario in dataset.yaml",
        )
    }
}
