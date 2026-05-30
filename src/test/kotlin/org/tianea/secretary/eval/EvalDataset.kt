package org.tianea.secretary.eval

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * 한 평가 케이스. [id]는 Langfuse dataset-item upsert 키로 재사용된다.
 *
 * @property id 안정적 식별자(파일에서 고정). 변경하면 Langfuse에 새 item이 생성된다.
 * @property input 어시스턴트에 보낼 질문.
 * @property referenceAnswer judge가 채점 기준으로 삼는 기대 정답.
 * @property tags 분류용 태그(선택).
 */
data class EvalItem(
    val id: String,
    val input: String,
    val referenceAnswer: String,
    val tags: List<String> = emptyList(),
)

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
