package org.tianea.secretary.eval

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * 멀티턴 시나리오의 한 발화.
 *
 * @property input 해당 턴에서 어시스턴트에 보낼 입력.
 * @property session 같은 세션 라벨끼리는 동일 TSID(같은 대화)로 묶이고, 라벨이 다르면 같은 chatId 내 별개 세션이 된다.
 */
data class Turn(val input: String, val session: String = "main")

/**
 * 한 평가 케이스. [id]는 Langfuse dataset-item upsert 키로 재사용된다.
 *
 * 단일턴은 [input]만 채우고, 멀티턴 시나리오는 [turns]로 발화 순서를 기술한다. [resolvedTurns]가 둘을 통일해 항상 1개 이상의 [Turn]을
 * 돌려준다.
 *
 * @property id 안정적 식별자(파일에서 고정). 변경하면 Langfuse에 새 item이 생성된다.
 * @property input 단일턴 입력. [turns]가 있으면 무시된다(빈 문자열 기본값).
 * @property referenceAnswer judge가 채점 기준으로 삼는 기대 정답.
 * @property tags 분류용 태그(선택).
 * @property turns 멀티턴 발화 순서. 비어 있으면 [input]을 단일 턴으로 본다.
 * @property scenarioId 멀티턴 시나리오 식별자(선택). 보통 [id]와 동일하게 둔다.
 */
data class EvalItem(
    val id: String,
    val input: String = "",
    val referenceAnswer: String,
    val tags: List<String> = emptyList(),
    val turns: List<Turn> = emptyList(),
    val scenarioId: String? = null,
) {
    /** [turns]가 있으면 그대로, 없으면 [input] 하나짜리 단일 턴으로 정규화한다. */
    fun resolvedTurns(): List<Turn> = turns.ifEmpty { listOf(Turn(input)) }
}

/** 레포에 버전 관리되는 평가 데이터셋. 회귀 테스트의 재현성을 위해 Langfuse가 아니라 파일이 source of truth. */
data class EvalDataset(val items: List<EvalItem>)

/** `src/test/resources/eval/dataset.yaml`을 [EvalDataset]으로 로드한다. */
object EvalDatasetLoader {
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    fun load(resourcePath: String = "/eval/dataset.yaml"): EvalDataset =
        EvalDatasetLoader::class.java.getResourceAsStream(resourcePath)?.use {
            mapper.readValue(it)
        } ?: error("eval dataset not found on classpath: $resourcePath")
}
