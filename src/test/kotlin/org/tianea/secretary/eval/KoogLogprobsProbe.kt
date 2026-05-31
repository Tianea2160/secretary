package org.tianea.secretary.eval

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import ai.koog.spring.ai.chat.SpringAiLLMClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/**
 * **검증 전용 프로브** — "Koog `PromptExecutor` 경로(Spring AI 브릿지)로는 logprobs를 받을 수 없다"는 주장을 실측으로 확인한다.
 *
 * jar 디컴파일상 [SpringAiLLMClient.execute]는 Spring AI `Generation`을 Koog `Message.Assistant`로 변환하면서
 * text·reasoning·toolCall·token usage만 추출하고 logprobs는 버린다. 이 테스트는 같은 Ollama 모델에 대해:
 * - **(A) Koog 브릿지 경로**: `Message.Assistant`를 통째로 덤프해 logprobs 흔적이 전혀 없음을 보인다.
 * - **(B) Ollama `/api/chat` 직접 호출**: `logprobs:true`로 토큰별 분포가 실제로 내려옴을 보여 대조한다.
 *
 * Spring 컨텍스트(Postgres 등) 없이 **브릿지 한 겹만** 격리하려고 `OllamaChatModel`을 수동 구성한다. Ollama가 떠 있지 않으면 스킵한다.
 * 검증이 끝나면 삭제해도 되는 일회성 코드.
 */
class KoogLogprobsProbe {
    private val log = LoggerFactory.getLogger(javaClass)

    private val baseUrl = "http://localhost:11434"
    private val modelId = "qwen3:4b-instruct-2507-q4_K_M"

    @Test
    fun koogBridgeDropsLogprobsWhileOllamaReturnsThem() {
        assumeTrue(ollamaReachable(), "Ollama가 $baseUrl 에 떠 있지 않아 스킵")

        val gradePrompt =
            prompt("logprobs-probe", LLMParams(temperature = 0.0)) {
                system("You are a grader. Output ONLY a single integer from 1 to 5.")
                user("Rate how blue the sky is on a scale of 1 to 5. Output only the digit.")
            }

        val koogModel =
            LLModel(
                provider = LLMProvider.Ollama,
                id = modelId,
                capabilities = listOf(LLMCapability.Temperature, LLMCapability.Schema.JSON.Basic),
                contextLength = 262_144,
            )

        val client =
            SpringAiLLMClient.builder()
                .chatModel(ollamaChatModel())
                .provider(LLMProvider.Ollama)
                .build()

        val assistant = runBlocking { client.execute(gradePrompt, koogModel, emptyList()) }

        val dump = assistant.toString()
        log.info("[probe-A] === Koog Message.Assistant (full) ===\n{}", dump)
        log.info("[probe-A] parts       = {}", assistant.parts)
        log.info("[probe-A] finishReason= {}", assistant.finishReason)
        log.info("[probe-A] metaInfo    = {}", assistant.metaInfo)
        log.info("[probe-A] metadata    = {}", assistant.metaInfo.metadata)
        log.info("[probe-A] rawResponse = {}", assistant.rawResponse)
        log.info(
            "[probe-A] >>> 'logprob' 문자열이 응답 객체 어디에 있나? {}",
            if (dump.contains("logprob", ignoreCase = true)) "발견됨(예상과 다름!)" else "전혀 없음(예상대로)",
        )

        val direct = directOllamaChatWithLogprobs()
        log.info("[probe-B] === Ollama /api/chat 직접 호출 (logprobs:true) ===\n{}", direct)
        log.info(
            "[probe-B] >>> 직접 호출 응답에 'logprob'이 있나? {}",
            if (direct.contains("logprob", ignoreCase = true)) "있음(예상대로)" else "없음(예상과 다름!)",
        )
    }

    private fun ollamaChatModel(): OllamaChatModel =
        OllamaChatModel.builder()
            .ollamaApi(OllamaApi.builder().baseUrl(baseUrl).build())
            .defaultOptions(OllamaChatOptions.builder().model(modelId).temperature(0.0).build())
            .build()

    private fun directOllamaChatWithLogprobs(): String =
        RestClient.create()
            .post()
            .uri("$baseUrl/api/chat")
            .body(
                mapOf(
                    "model" to modelId,
                    "messages" to
                        listOf(
                            mapOf(
                                "role" to "system",
                                "content" to
                                    "You are a grader. Output ONLY a single integer from 1 to 5.",
                            ),
                            mapOf(
                                "role" to "user",
                                "content" to
                                    "Rate how blue the sky is on a scale of 1 to 5. Output only the digit.",
                            ),
                        ),
                    "stream" to false,
                    "logprobs" to true,
                    "top_logprobs" to 5,
                    "options" to mapOf("num_predict" to 1, "temperature" to 0.0),
                )
            )
            .retrieve()
            .body<String>() ?: "(empty)"

    private fun ollamaReachable(): Boolean =
        runCatching {
                RestClient.create().get().uri("$baseUrl/api/version").retrieve().body<String>()
            }
            .isSuccess
}
