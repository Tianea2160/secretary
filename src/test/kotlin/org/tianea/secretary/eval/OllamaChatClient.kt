package org.tianea.secretary.eval

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
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
 * Ollama `/api/chat` 선언적 클라이언트 — **logprobs 수신 전용**.
 *
 * Koog `PromptExecutor`(Spring AI 브릿지)는 응답에서 logprobs를 버리므로(`KoogLogprobsProbe`로 실측 확인), G-Eval
 * 채점([EvalJudge])은 Koog를 우회해 Ollama HTTP API를 직접 호출하고 토큰별 분포를 받는다. [LangfuseClient]와 같은
 * `@HttpExchange` + [HttpServiceProxyFactory] 선언형 패턴(추가 의존성 0).
 */
@HttpExchange(url = "/api", accept = ["application/json"], contentType = "application/json")
interface OllamaChatClient {
    /** non-stream 채팅. `logprobs:true`면 응답 [OllamaChatResponse.logprobs]에 토큰별 분포가 담긴다. */
    @PostExchange("/chat") fun chat(@RequestBody body: OllamaChatRequest): OllamaChatResponse

    companion object {
        /**
         * baseUrl로 [OllamaChatClient] 프록시를 만든다.
         *
         * 응답에 미지 필드(`total_duration` 등)가 와도 깨지지 않도록 `FAIL_ON_UNKNOWN_PROPERTIES=false`로 구성한다.
         */
        fun create(baseUrl: String): OllamaChatClient {
            val mapper =
                jacksonObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            val restClient =
                RestClient.builder()
                    .baseUrl(baseUrl)
                    .messageConverters { converters ->
                        converters.removeIf { it is MappingJackson2HttpMessageConverter }
                        converters.add(MappingJackson2HttpMessageConverter(mapper))
                    }
                    .build()
            val factory =
                HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
            return factory.createClient<OllamaChatClient>()
        }
    }
}

/**
 * `/api/chat` 요청. score 토큰만 필요하므로 [options]의 `num_predict`를 작게 두고, `logprobs`로 생성 토큰 텍스트를
 * 받는다(judge는 그 토큰에서 첫 1~5 점수를 읽는다).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = false,
    val logprobs: Boolean = true,
    val options: OllamaOptions = OllamaOptions(),
)

data class OllamaMessage(val role: String, val content: String)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OllamaOptions(
    @JsonProperty("num_predict") val numPredict: Int = 4,
    val temperature: Double = 0.0,
    @JsonProperty("seed") val seed: Int? = null,
)

/** `/api/chat` 응답. [logprobs]는 생성된 토큰마다 한 항목이며, judge는 그 토큰 텍스트에서 첫 1~5 점수를 읽는다. */
data class OllamaChatResponse(
    val message: OllamaMessage,
    val logprobs: List<OllamaTokenLogprobs> = emptyList(),
)

data class OllamaTokenLogprobs(val token: String, val logprob: Double)
