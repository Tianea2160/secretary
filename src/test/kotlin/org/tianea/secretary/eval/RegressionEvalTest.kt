package org.tianea.secretary.eval

import io.hypersistence.tsid.TSID
import java.time.Instant
import java.util.UUID
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.tianea.secretary.core.agent.AssistantRunner

/**
 * 배포 전 회귀 방지 게이트.
 *
 * 고정 데이터셋([EvalDatasetLoader])을 실제 [AssistantRunner] 경로로 실행하고, 각 응답을 [EvalJudge](Ollama
 * LLM-as-a-judge)로 기준답안 대비 채점한다. 결과(trace·score·dataset-run)를 Langfuse에 기록해 Experiments 뷰에서 run끼리
 * 비교할 수 있게 하고, **평균 점수가 임계값 미만이면 테스트를 실패시켜 배포를 막는다**.
 *
 * 라이브 LLM(Ollama) + Langfuse + DB가 필요하므로 일반 유닛 테스트와 분리한다. `LANGFUSE_PUBLIC_KEY`가 설정된 경우에만
 * 동작(opt-in). 관련 env:
 * - `LANGFUSE_BASE_URL` / `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` — Langfuse 연결(필수).
 * - `EVAL_RUN_NAME` — Experiments 비교용 run 이름(미설정 시 타임스탬프).
 * - `EVAL_SCORE_THRESHOLD` — 평균 점수 합격선(기본 0.7).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LANGFUSE_PUBLIC_KEY", matches = ".+")
class RegressionEvalTest {
    @Autowired private lateinit var runner: AssistantRunner

    @Value("\${spring.ai.ollama.base-url}") private lateinit var ollamaBaseUrl: String

    @Value("\${spring.ai.ollama.chat.options.model}") private lateinit var judgeModel: String

    private val log = LoggerFactory.getLogger(javaClass)

    @Test
    fun regressionGate() {
        val baseUrl = requireEnv("LANGFUSE_BASE_URL")
        val publicKey = requireEnv("LANGFUSE_PUBLIC_KEY")
        val secretKey = requireEnv("LANGFUSE_SECRET_KEY")
        val runName = System.getenv("EVAL_RUN_NAME") ?: "local-${Instant.now().epochSecond}"
        val threshold = System.getenv("EVAL_SCORE_THRESHOLD")?.toDouble() ?: DEFAULT_THRESHOLD

        val dataset = EvalDatasetLoader.load()
        val langfuse = LangfuseClient.create(baseUrl, publicKey, secretKey)
        val judge = EvalJudge(OllamaChatClient.create(ollamaBaseUrl), judgeModel)

        assertJudgeDiscriminates(judge)

        langfuse.createDataset(
            CreateDatasetRequest(name = DATASET_NAME, description = "Secretary regression eval set")
        )

        val scores = mutableListOf<Double>()
        for (item in dataset.items) {
            langfuse.upsertDatasetItem(
                CreateDatasetItemRequest(
                    datasetName = DATASET_NAME,
                    id = item.id,
                    input = item.input,
                    expectedOutput = item.referenceAnswer,
                    metadata = mapOf("tags" to item.tags),
                )
            )

            val sessionId = TSID.fast().toString()
            val output = runner.run(EVAL_CHAT_ID, sessionId, item.input, null)
            val verdict = judge.judge(item.input, item.referenceAnswer, output)
            scores += verdict.score
            log.info("[eval] {} score={} :: {}", item.id, verdict.score, verdict.reasoning)

            val traceId = newTraceId()
            recordToLangfuse(langfuse, traceId, runName, item, output, verdict)
        }

        val mean = scores.average()
        log.info(
            "[eval] run={} mean={} threshold={} items={}",
            runName,
            mean,
            threshold,
            scores.size,
        )
        assertTrue(
            mean >= threshold,
            "Regression gate failed: mean score $mean < threshold $threshold (run=$runName). " +
                "See Langfuse dataset '$DATASET_NAME' for per-item detail.",
        )
    }

    /**
     * 한 케이스 결과를 Langfuse에 기록한다: trace(input/output) + score를 같은 배치로 보내 trace가 먼저 존재하도록 보장하고,
     * dataset-run-item으로 run·item·trace를 연결한다.
     */
    private fun recordToLangfuse(
        langfuse: LangfuseClient,
        traceId: String,
        runName: String,
        item: EvalItem,
        output: String,
        verdict: JudgeVerdict,
    ) {
        val now = Instant.now().toString()
        val response =
            langfuse.ingest(
                IngestionRequest(
                    batch =
                        listOf(
                            IngestionEvent(
                                id = UUID.randomUUID().toString(),
                                type = "trace-create",
                                timestamp = now,
                                body =
                                    TraceBody(
                                        id = traceId,
                                        name = "eval:${item.id}",
                                        input = item.input,
                                        output = output,
                                        timestamp = now,
                                        metadata = mapOf("runName" to runName, "tags" to item.tags),
                                    ),
                            ),
                            IngestionEvent(
                                id = UUID.randomUUID().toString(),
                                type = "score-create",
                                timestamp = now,
                                body =
                                    ScoreBody(
                                        id = UUID.randomUUID().toString(),
                                        name = SCORE_NAME,
                                        value = verdict.score,
                                        traceId = traceId,
                                        comment = verdict.reasoning,
                                    ),
                            ),
                        )
                )
            )
        if (response.errors.isNotEmpty()) {
            log.warn("[eval] Langfuse ingestion errors for {}: {}", item.id, response.errors)
        }

        langfuse.createDatasetRunItem(
            CreateDatasetRunItemRequest(
                runName = runName,
                datasetItemId = item.id,
                traceId = traceId,
            )
        )
    }

    /**
     * judge가 정답과 오답을 실제로 구분하는지 매 실행마다 검증하는 음성 대조군(negative control).
     *
     * 데이터셋 점수가 거의 1.0에 몰려 있어, judge가 망가지거나(logprobs 경로 변경, 모델 회귀) 항상 만점을 주는 상태가 되면 회귀 게이트는 헛되이
     * 통과한다. 명백히 틀린 답을 채점시켜 점수가 [SANITY_WRONG_MAX] 이하로 떨어지지 않으면 게이트 자체가 무의미하다고 보고 실패시킨다.
     */
    private fun assertJudgeDiscriminates(judge: EvalJudge) {
        val verdict = judge.judge(SANITY_QUESTION, SANITY_REFERENCE, SANITY_WRONG_ANSWER)
        log.info(
            "[eval] judge sanity (deliberately wrong) score={} :: {}",
            verdict.score,
            verdict.reasoning,
        )
        assertTrue(
            verdict.score <= SANITY_WRONG_MAX,
            "Judge sanity check failed: a deliberately wrong answer scored ${verdict.score} " +
                "(> $SANITY_WRONG_MAX). The judge cannot distinguish wrong answers, so the regression " +
                "gate would pass vacuously. Verify the judge model and logprobs path before trusting scores.",
        )
    }

    private fun requireEnv(name: String): String =
        System.getenv(name) ?: error("$name must be set to run the regression eval")

    private fun newTraceId(): String =
        (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").take(32)

    private companion object {
        const val DATASET_NAME = "secretary-regression"
        const val SCORE_NAME = "correctness"
        const val DEFAULT_THRESHOLD = 0.7
        const val EVAL_CHAT_ID = 0L

        const val SANITY_QUESTION = "What is the capital of France?"
        const val SANITY_REFERENCE = "The capital of France is Paris."
        const val SANITY_WRONG_ANSWER = "The capital of France is Berlin."
        const val SANITY_WRONG_MAX = 0.3
    }
}
