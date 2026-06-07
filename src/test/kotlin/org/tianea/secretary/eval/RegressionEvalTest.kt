package org.tianea.secretary.eval

import io.hypersistence.tsid.TSID
import java.time.Instant
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
 * 비교할 수 있게 하고, **태그 코호트별 평균 점수가 임계값 미만이면 테스트를 실패시켜 배포를 막는다**(전체 평균은 로깅만 — 쉬운 항목이 노하우 회귀를 가리지 않게
 * 코호트별로 게이트한다).
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
        assertJudgeCalibrated(judge)

        langfuse.createDataset(
            CreateDatasetRequest(name = DATASET_NAME, description = "Secretary regression eval set")
        )

        val cohortScores = mutableMapOf<String, MutableList<Double>>()
        val allScores = mutableListOf<Double>()
        dataset.items.forEachIndexed { index, item ->
            langfuse.upsertDatasetItem(
                CreateDatasetItemRequest(
                    datasetName = DATASET_NAME,
                    id = item.id,
                    input = item.input,
                    expectedOutput = item.referenceAnswer,
                    metadata = mapOf("tags" to item.tags),
                )
            )

            val chatId = EVAL_CHAT_ID_BASE + index
            val tsidForLabel = mutableMapOf<String, String>()
            val turns = item.resolvedTurns()
            var lastOutput = ""
            for (turn in turns) {
                val tsid = tsidForLabel.getOrPut(turn.session) { TSID.fast().toString() }
                lastOutput = runner.run(chatId, tsid, turn.input, null)
            }
            val lastTurn = turns.last()
            val verdict = judge.judge(lastTurn.input, item.referenceAnswer, lastOutput)

            allScores += verdict.score
            for (tag in item.tags) {
                cohortScores.getOrPut(tag) { mutableListOf() }.add(verdict.score)
            }
            if (item.tags.any { it in ABILITY_TAGS }) {
                cohortScores.getOrPut(KNOWHOW_COHORT) { mutableListOf() }.add(verdict.score)
            }
            log.info("[eval] {} score={} :: {}", item.id, verdict.score, verdict.reasoning)

            val traceId = newTraceId()
            recordToLangfuse(langfuse, traceId, runName, item, lastTurn.input, lastOutput, verdict)
        }

        val overallMean = if (allScores.isEmpty()) 0.0 else allScores.average()
        log.info(
            "[eval] run={} overallMean={} items={} (overall mean is logged only, not gated)",
            runName,
            overallMean,
            allScores.size,
        )

        val failures = mutableListOf<String>()
        for ((tag, scores) in cohortScores.toSortedMap()) {
            val cohortThreshold = thresholdFor(tag, threshold)
            val cohortMean = scores.average()
            if (scores.size < MIN_GATED_COHORT_SIZE) {
                log.warn(
                    "[eval] cohort '{}' has only {} item(s) (< {}); mean={} NOT gated.",
                    tag,
                    scores.size,
                    MIN_GATED_COHORT_SIZE,
                    cohortMean,
                )
                continue
            }
            log.info(
                "[eval] cohort '{}' mean={} threshold={} items={}",
                tag,
                cohortMean,
                cohortThreshold,
                scores.size,
            )
            if (cohortMean < cohortThreshold) {
                failures.add(
                    "cohort '$tag' mean $cohortMean < threshold $cohortThreshold (${scores.size} items)"
                )
            }
        }

        assertTrue(
            failures.isEmpty(),
            "Regression gate failed for ${failures.size} cohort(s) (run=$runName):\n" +
                failures.joinToString("\n") { "  - $it" } +
                "\nSee Langfuse dataset '$DATASET_NAME' for per-item detail.",
        )
    }

    /**
     * 코호트 임계값 선택. 검증된 일반 임계값([defaultThreshold])을 기본으로 쓰되, know-how 능력 코호트 ([ABILITY_TAGS])와 이들을 합친
     * [KNOWHOW_COHORT] super-cohort는 아직 검증되지 않은 더 낮은 [KNOWHOW_COHORT_THRESHOLD]를 적용한다.
     */
    private fun thresholdFor(tag: String, defaultThreshold: Double): Double =
        if (tag == KNOWHOW_COHORT || tag in ABILITY_TAGS) KNOWHOW_COHORT_THRESHOLD
        else defaultThreshold

    /**
     * judge가 정답성을 **순서대로** 채점하고(ordinal ladder), 기준답안을 그대로 먹이면 높은 점수를, 같은 케이스를 반복해도 분산이 작게
     * 나오는지(temp>0이라 '항상 동일' 불변식을 분산 상한으로 대체) 검증한다.
     *
     * @param judge 라이브 [EvalJudge]. 실제 [EvalJudge.judge] 호출로 측정한다.
     */
    private fun assertJudgeCalibrated(judge: EvalJudge) {
        val full = judge.judge(LADDER_QUESTION, LADDER_REFERENCE, LADDER_FULL_ANSWER)
        val partial = judge.judge(LADDER_QUESTION, LADDER_REFERENCE, LADDER_PARTIAL_ANSWER)
        val wrong = judge.judge(LADDER_QUESTION, LADDER_REFERENCE, LADDER_WRONG_ANSWER)
        log.info(
            "[eval] judge ladder full={} partial={} wrong={}",
            full.score,
            partial.score,
            wrong.score,
        )
        assertTrue(
            full.score - partial.score >= LADDER_MIN_GAP,
            "Judge calibration (ladder) failed: full ${full.score} not >= partial ${partial.score} " +
                "+ $LADDER_MIN_GAP. Judge does not reward completeness; tune LADDER_MIN_GAP or rubric.",
        )
        assertTrue(
            partial.score - wrong.score >= LADDER_MIN_GAP,
            "Judge calibration (ladder) failed: partial ${partial.score} not >= wrong ${wrong.score} " +
                "+ $LADDER_MIN_GAP. Judge does not penalize wrong answers; tune LADDER_MIN_GAP or rubric.",
        )

        val anchor = judge.judge(LADDER_QUESTION, LADDER_REFERENCE, LADDER_REFERENCE)
        log.info("[eval] judge anchor (reference verbatim) score={}", anchor.score)
        assertTrue(
            anchor.score >= HIGH_ANCHOR_MIN,
            "Judge calibration (anchor) failed: reference-verbatim answer scored ${anchor.score} " +
                "(< $HIGH_ANCHOR_MIN). Judge underrates a verbatim-correct answer; inspect the rubric.",
        )

        log.info("[eval] judge variance (anchor) sampleStdDev={}", anchor.sampleStdDev)
        assertTrue(
            anchor.sampleStdDev <= MAX_SAMPLE_STDDEV,
            "Judge calibration (variance) failed: sampleStdDev ${anchor.sampleStdDev} " +
                "(> $MAX_SAMPLE_STDDEV) on a fixed case. Decode noise is too high; lower JUDGE_TEMPERATURE " +
                "or raise SAMPLE_COUNT (then retune MAX_SAMPLE_STDDEV).",
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
        input: String,
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
                                id = TSID.fast().toString(),
                                type = "trace-create",
                                timestamp = now,
                                body =
                                    TraceBody(
                                        id = traceId,
                                        name = "eval:${item.id}",
                                        input = input,
                                        output = output,
                                        timestamp = now,
                                        metadata = mapOf("runName" to runName, "tags" to item.tags),
                                    ),
                            ),
                            IngestionEvent(
                                id = TSID.fast().toString(),
                                type = "score-create",
                                timestamp = now,
                                body =
                                    ScoreBody(
                                        id = TSID.fast().toString(),
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

    private fun newTraceId(): String = TSID.fast().toString()

    private companion object {
        const val DATASET_NAME = "secretary-regression"
        const val SCORE_NAME = "correctness"
        const val DEFAULT_THRESHOLD = 0.7

        /**
         * 시나리오마다 고유 chatId를 주는 베이스. `base + index`로 케이스 간 know-how 공유를 막는다(각 시나리오는 독립된 대화 상태에서
         * 재생되어야 한다).
         */
        const val EVAL_CHAT_ID_BASE = 1_000_000L

        /** 하드 게이트할 코호트 최소 크기. 이보다 작으면 경고만 하고 게이트하지 않는다. */
        const val MIN_GATED_COHORT_SIZE = 3

        /**
         * know-how 능력 코호트([ABILITY_TAGS])용 임계값. 일반 토픽 코호트보다 낮게 잡은 **미검증** 값으로, 첫 실행 결과를 보고 조정해야 한다.
         */
        const val KNOWHOW_COHORT_THRESHOLD = 0.5

        /** 능력(ability) 코호트 태그. 한 item은 자신이 가진 모든 능력 태그 코호트에 동시에 속한다. */
        val ABILITY_TAGS =
            setOf("info-extraction", "multi-session", "temporal", "knowledge-update", "abstention")

        /**
         * know-how 능력 태그를 하나라도 가진 모든 item을 한 번씩 모으는 합산 코호트 키. 개별 능력 코호트는 항목 수가
         * 적어([MIN_GATED_COHORT_SIZE] 미만) 경고만 되는 경우가 많아, 어떤 노하우 회귀도 게이트를 빠져나가지 않도록 이 super-cohort를
         * 항상 함께 게이트한다.
         */
        const val KNOWHOW_COHORT = "know-how"

        const val SANITY_QUESTION = "What is the capital of France?"
        const val SANITY_REFERENCE = "The capital of France is Paris."
        const val SANITY_WRONG_ANSWER = "The capital of France is Berlin."
        const val SANITY_WRONG_MAX = 0.3

        const val LADDER_QUESTION = "What are the three additive primary colors of light?"
        const val LADDER_REFERENCE = "The three additive primary colors are red, green, and blue."
        const val LADDER_FULL_ANSWER = "Red, green, and blue."
        const val LADDER_PARTIAL_ANSWER = "Red and green."
        const val LADDER_WRONG_ANSWER = "Cyan, magenta, yellow."

        /** ordinal ladder 단계 간 최소 점수 간격. 느슨하게 잡았고 첫 실행 후 조정한다. */
        const val LADDER_MIN_GAP = 0.15

        /** 기준답안을 그대로 먹였을 때 받아야 하는 하한 점수(high anchor). */
        const val HIGH_ANCHOR_MIN = 0.8

        /** 고정 케이스 한 건의 표본 표준편차 상한. temp>0이라 '매 실행 동일' 불변식을 쓸 수 없어 이 분산 상한으로 대체한다. 첫 실행 후 조정. */
        const val MAX_SAMPLE_STDDEV = 1.0
    }
}
