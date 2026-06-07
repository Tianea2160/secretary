package org.tianea.secretary.eval

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.client.RestClient
import org.springframework.web.client.support.RestClientAdapter
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import org.springframework.web.service.invoker.HttpServiceProxyFactory
import org.springframework.web.service.invoker.createClient

/**
 * Langfuse v3 public REST API의 선언적 클라이언트.
 *
 * Langfuse는 Kotlin SDK가 없어 HTTP를 직접 호출한다. Spring `@HttpExchange` 인터페이스 + [HttpServiceProxyFactory]
 * 프록시로 OpenFeign 스타일의 선언형 클라이언트를 구성한다(추가 의존성 0).
 *
 * 경로 주의: **datasets만 `/v2/`**, 나머지(dataset-items, dataset-run-items, ingestion)는 비버전. score 전용
 * 엔드포인트는 v3에서 제거되어 **score는 ingestion `score-create`로만** 생성한다.
 */
@HttpExchange(url = "/api/public", accept = ["application/json"], contentType = "application/json")
interface LangfuseClient {
    /** 데이터셋 생성/업데이트. name 기준 upsert(중복 시 409 없음). */
    @PostExchange("/v2/datasets")
    fun createDataset(@RequestBody body: CreateDatasetRequest): DatasetResponse

    /** 데이터셋 item upsert. 직접 부여한 [CreateDatasetItemRequest.id] 기준 멱등. */
    @PostExchange("/dataset-items")
    fun upsertDatasetItem(@RequestBody body: CreateDatasetItemRequest): DatasetItemResponse

    /** run-name + dataset item + trace를 연결 — Experiments 비교 뷰를 채운다. */
    @PostExchange("/dataset-run-items")
    fun createDatasetRunItem(@RequestBody body: CreateDatasetRunItemRequest): DatasetRunItemResponse

    /** trace-create / score-create 이벤트 배치 전송. */
    @PostExchange("/ingestion") fun ingest(@RequestBody body: IngestionRequest): IngestionResponse

    companion object {
        /**
         * Basic auth(publicKey:secretKey) + baseUrl로 [LangfuseClient] 프록시를 만든다.
         *
         * 응답에 미지의 필드가 와도 깨지지 않도록 `FAIL_ON_UNKNOWN_PROPERTIES=false`로 구성한다.
         */
        fun create(baseUrl: String, publicKey: String, secretKey: String): LangfuseClient {
            val mapper =
                jacksonObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            val restClient =
                RestClient.builder()
                    .baseUrl(baseUrl)
                    .defaultHeaders { it.setBasicAuth(publicKey, secretKey) }
                    .messageConverters { converters ->
                        converters.removeIf { it is MappingJackson2HttpMessageConverter }
                        converters.add(MappingJackson2HttpMessageConverter(mapper))
                    }
                    .build()
            val factory =
                HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
            return factory.createClient<LangfuseClient>()
        }
    }
}

data class CreateDatasetRequest(val name: String, val description: String? = null)

data class DatasetResponse(val id: String? = null, val name: String? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreateDatasetItemRequest(
    val datasetName: String,
    val id: String,
    val input: String,
    val expectedOutput: String,
    val metadata: Map<String, Any?>? = null,
)

data class DatasetItemResponse(val id: String? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreateDatasetRunItemRequest(
    val runName: String,
    val datasetItemId: String,
    val traceId: String,
    val runDescription: String? = null,
    val metadata: Map<String, Any?>? = null,
)

data class DatasetRunItemResponse(val id: String? = null, val datasetRunId: String? = null)

data class IngestionRequest(val batch: List<IngestionEvent>)

/**
 * ingestion 배치의 단위 이벤트.
 *
 * @property id 이벤트 멱등 키(TSID). trace/score body의 id와는 별개.
 * @property type `"trace-create"` 또는 `"score-create"`.
 * @property timestamp ISO-8601(밀리초 이상).
 * @property body [TraceBody] 또는 [ScoreBody].
 */
data class IngestionEvent(val id: String, val type: String, val timestamp: String, val body: Any)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TraceBody(
    val id: String,
    val name: String? = null,
    val input: String? = null,
    val output: String? = null,
    val timestamp: String? = null,
    val metadata: Map<String, Any?>? = null,
)

/** score-create body. [id]는 **필수** — 생략하면 ingestion이 201로 받지만 worker가 조용히 드롭한다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScoreBody(
    val id: String,
    val name: String,
    val value: Double,
    val traceId: String,
    val comment: String? = null,
    val dataType: String = "NUMERIC",
)

data class IngestionResponse(
    val successes: List<Map<String, Any?>> = emptyList(),
    val errors: List<Map<String, Any?>> = emptyList(),
)
