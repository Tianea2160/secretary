# 자가성장 대화 에이전트 평가지표

> 사용자와의 대화에서 노하우(선호·사실·절차)를 축적하며 스스로 성장하는 secretary 에이전트를,
> **객관적이고 재현 가능한 운영 지표**로 평가하기 위한 설계 문서.
> 학술 벤치마크·논문 근거를 우선하되, 이 repo의 실제 코드 경로(`know_how` 테이블, `eval` 하니스)에 적용 가능한 형태로 종합한다.

## 0. 범위와 전제

- **성장 메커니즘**: 대화 기반 메모리 축적 + 노하우의 **사용 횟수(`use_count`)·경과 시간(`last_used_at`)** 신호 기반 능동 최적화. (가중 학습/파인튜닝 없음 — API-only 모델 전제.)
- **1차 용도**: 학술 벤치마크 제출이 아니라 **이 봇에 붙일 운영 지표**. 측정 가능·로깅 가능·재현 가능이 1순위.
- **설계 초점**: ① 성장/학습 속도 ② 개인화 정확도. 나머지(망각·일관성·노하우 효용)는 이 둘을 신뢰성 있게 읽기 위한 보조축.

### 핵심 결론 (TL;DR)

자가성장 에이전트의 방어 가능한 평가는 **단일 점수가 아니라 네 가지 검증된 학술 전통을 조합한 다차원 대시보드**로 만든다.

| # | 측정 전통 | 대표 출처(1차) | 이 프로젝트에 주는 것 |
|---|-----------|----------------|------------------------|
| 1 | 장기기억·개인화 벤치마크 | LongMemEval(ICLR'25), LoCoMo(ACL'24), PerLTQA(SIGHAN'24) | 능력별 정확도 분해, recall@k vs QA 분리, 메모리 타입 스키마 |
| 2 | 자가개선 에이전트 프로토콜 | Reflexion(NeurIPS'23), Self-Refine(NeurIPS'23), ExpeL(AAAI'24) | **개선율/learning curve** — 가중치 갱신 없이 반복 시도 간 향상 측정 |
| 3 | 연속학습(continual learning) | GEM(NeurIPS'17), Kemker(AAAI'18) | ACC·BWT·FWT **정확한 수식** — 망각·retention·전이 |
| 4 | 스킬/절차지식 귀납 | ExpeL, Voyager(arXiv'23) | 대화에서 노하우 추출·재호출 능력의 평가 골격 |

> ⚠️ **가장 큰 리스크는 LLM-as-a-judge 자체의 신뢰성**이다(§5). GPT-4o judge는 LongMemEval 기본 채점 방식이자 이 repo의 `EvalJudge.kt` 패턴이지만, 사람 라벨 대비 캘리브레이션 없이는 전체 평가의 타당성이 무너진다.

---

## 1. 측정 철학 — 왜 단일 점수가 아닌가

LongMemEval의 핵심 교훈: 장기기억은 **하나의 점수로 뭉치면 안 되고, 독립적으로 채점 가능한 능력들로 분해**해야 한다([arXiv:2410.10813](https://arxiv.org/abs/2410.10813), [official repo](https://github.com/xiaowu0162/LongMemEval)). 5가지 능력:

1. **정보 추출(information extraction)** — 사용자가 알려준 사실을 정확히 회상
2. **멀티세션 추론(multi-session reasoning)** — 여러 세션에 걸친 정보 종합
3. **시간 추론(temporal reasoning)** — "지난달 vs 이번 달" 같은 시점 비교
4. **지식 갱신(knowledge update)** — "마음 바뀌었어, 이제 X가 아니라 Y야"를 반영
5. **거부(abstention)** — 모르는/답할 수 없는 것을 지어내지 않고 모른다고 함

근거가 되는 실증: 상용 챗봇·롱컨텍스트 LLM은 지속적 멀티세션 상호작용에서 정보 회상 정확도가 **약 30% 하락**한다(LongMemEval abstract, 3-0 검증). → 명시적 메모리 계층을 두고, **메모리 회상(recall)과 생성 품질(QA accuracy)을 분리 측정**할 근거.

**적용**: 모든 평가 케이스에 위 5능력 중 어떤 것을 시험하는지 라벨을 달고, 단일 평균이 아니라 **5-way 정확도 분해**로 보고한다. 이 repo의 `EvalItem.tags`를 능력 라벨로 재사용하면 된다.

---

## 2. 개인화 정확도 (초점 ②)

### 2.1 메모리 검색 품질 — Recall@k / Precision@k / MRR

메모리 파이프라인은 **인덱싱 / 검색 / 읽기** 단계로 분해되며, 검색 단계 품질과 최종 답변 정확도는 **별도 지표**로 봐야 한다(LongMemEval, 3-0). 검색이 실패한 건지(retrieval) 읽기가 실패한 건지(reading)를 분리 진단할 수 있다.

평가 케이스마다 "정답에 필요한 노하우/메모리 아이템 집합"(relevant set, $R$)을 라벨링하고, 검색기가 반환한 top-k($K$)에 대해:

```
Recall@k    = |R ∩ K| / |R|              # 필요한 메모리를 얼마나 건졌나
Precision@k = |R ∩ K| / k                # 건진 것 중 실제 관련 비율
MRR         = (1/N) · Σ_i (1 / rank_i)    # 첫 관련 아이템의 평균 역순위
```

- **로깅 필요**: 턴마다 retrieved candidate ID + 유사도/재랭킹 score (이 repo의 `ScoredKnowHow`가 이미 보유), 그리고 케이스별 relevant set(오프라인 라벨).
- **한계**: relevant set 라벨링이 사람 손을 탄다. 초기엔 자동 합성(노하우를 의도적으로 심은 시나리오)으로 우회.

### 2.2 답변 정확도 — 5능력별 분해

`EvalJudge`(temp=0, structured `JudgeVerdict`)로 기준답안 대비 채점하되, **케이스 태그별로 평균을 쪼갠다**:

```
Accuracy(ability) = mean( judge.score | tag == ability )
```

여기서 특히 두 가지가 개인화의 핵심이다:

- **지식 갱신 정확도(knowledge-update)**: 사용자가 선호를 바꿨을 때 **옛 정보를 안 쓰고 새 정보를 쓰는 비율**. 자가성장 에이전트의 가장 흔한 실패(오래된 노하우 고착)를 직접 잡는다. → `know_how`의 `UPDATE` consolidation이 제대로 동작하는지와 직결.
- **거부 정확도(abstention)**: 메모리에 없는 개인 정보를 **지어내지 않는** 비율. LongMemEval은 `_abs` 변형으로 이를 별도 채점한다.

### 2.3 메모리 타입 스키마 (PerLTQA)

PerLTQA는 개인 장기기억을 **의미기억(semantic: 프로필·선호·관계)** vs **일화기억(episodic: 사건·대화)** 으로 나누고(Tulving 인지 분류), 파이프라인을 **분류 → 검색 → 합성** 3단계로 평가한다([arXiv:2402.16288](https://arxiv.org/abs/2402.16288), 3-0). 세 단계 출력이 그대로 **로깅 항목**이 된다: ① 부여된 메모리-타입 라벨 ② 검색된 후보 ③ 합성된 답변.

> ⚠️ PerLTQA는 원본이 중국어다. **원문 질문이 아니라 스키마·파이프라인 분해·합성 방법**만 차용하고, 한국어/영어 케이스는 직접 합성할 것.

---

## 3. 성장 / 학습 속도 (초점 ①)

가중치 갱신 없이(API-only) **반복 노출에 따른 향상**을 측정하는 방법은 이미 학술적으로 정립되어 있다.

### 3.1 개선율 / Learning Curve (Reflexion · ExpeL)

- **Reflexion**(NeurIPS'23, [arXiv:2303.11366](https://arxiv.org/abs/2303.11366)): 환경 피드백을 언어적 자기반성으로 바꿔 에피소드 메모리에 저장 → 다음 시도 컨텍스트로 주입. **같은 과제를 반복 시도하며 향상 폭(Δ)을 측정**하는 프로토콜.
- **ExpeL**(AAAI'24 Oral, [arXiv:2308.10144](https://arxiv.org/abs/2308.10144)): 과거 trajectory에서 자연어 인사이트를 추출(ADD/EDIT/UPVOTE/DOWNVOTE)하고 추론 시 kNN으로 재호출. **경험이 쌓일수록 성능이 일관되게 향상되는 learning curve**를 실증(Fig 6). 가중치 갱신이 없어 이 프로젝트에 직접 적용 가능.

**정의**: 안정적 `scenarioId`를 가진 시나리오 $s$를 시점/시도 $t_1 < t_2 < \dots < t_n$에 반복 실행하고 judge 점수 $score_s(t)$를 기록한다.

```
ImprovementRate(s) = ( score_s(t_n) − score_s(t_1) ) / (n − 1)     # 시도당 평균 향상
LearningSlope(s)   = OLS slope of score_s(t) over t                # 선형 추세 기울기
PassAtAttempt_k    = P( 첫 성공이 k번째 시도 이내 )                 # Reflexion식
```

- **로깅 필요**: `(scenarioId, trialIndex, timestamp, judge.score)`. 같은 상황을 시간에 걸쳐 재실행할 수 있어야 한다 — 이 repo의 `RegressionEvalTest`가 이미 고정 데이터셋을 재실행하는 구조라, **`trialIndex`/`runName`만 추가**하면 곡선을 그릴 수 있다.
- **한계**: ExpeL은 **수확 체감/plateau를 정량화하지 않았다**("일관된 양의 추세"일 뿐 단조 포화 증명 아님). 그리고 Reflexion·ExpeL은 성공 신호가 뚜렷한 과제(코드 테스트, ALFWorld, HotpotQA)에서 검증됐고 **열린 대화가 아니다** — 방법 템플릿으로는 타당하나 절대 향상 폭이 그대로 옮겨온다고 가정하지 말 것.

### 3.2 세션 내 정제 이득 (Self-Refine) — 단, 외부 신호 필수

Self-Refine(NeurIPS'23, [arXiv:2303.17651](https://arxiv.org/abs/2303.17651)): 생성→자기피드백→수정 루프로 7개 과제 평균 **약 +20%p**(test-time, 학습 없음). 

```
RefineGain = score(refined) − score(initial)
```

> ⚠️ **결정적 함정**: Huang et al. 2023(ICLR'24, [arXiv:2310.01798](https://arxiv.org/abs/2310.01798))은 **외부/오라클 피드백 없는 순수 자기수정은 추론 과제에서 정확도를 오히려 떨어뜨릴 수 있음**을 보였다. → **자기개선 지표는 외부 신호(사용자 확인·도구 결과·기준답안)가 있을 때만 신뢰**한다. 순수 self-judged 향상은 환상일 수 있다.

---

## 4. 망각 · 일관성 (보조축) — Continual Learning

새 노하우를 배우다 **옛 노하우를 잃지 않는지**(consistency over time)를 보는 정확한 수식이 GEM(Lopez-Paz & Ranzato, NeurIPS'17, [arXiv:1706.08840](https://arxiv.org/abs/1706.08840))에 있다. 시나리오를 task로 보고, **정확도 행렬** $R$을 채운다: $R_{i,j}$ = task $j$를 task $i$까지 학습한 후의 정확도.

```
ACC = (1/T) · Σ_i R_{T,i}                                   # 전체 평균 정확도

BWT = (1/(T−1)) · Σ_{i=1..T−1} ( R_{T,i} − R_{i,i} )         # 음수 클수록 = 파국적 망각
                                                            #  → "옛 선호를 잊었나" / 일관성

FWT = (1/(T−1)) · Σ_{i=2..T} ( R_{i−1,i} − b̄_i )            # 양수면 = 축적된 노하우가
                                                            #  새 상황에 전이됨 (b̄ = 무학습 baseline)
```

- **적용**: 반복되는 사용자 "상황"을 task로 정의하고 고정 eval 셋을 붙인다. 새 노하우 축적 후 **모든 과거 시나리오를 주기적으로 재채점**해 $R$을 채운다. BWT<0이면 새 학습이 옛 능력을 훼손(일관성 저하), FWT>0이면 노하우가 신규 상황에 도움.
- **보완 지표**: Kemker et al.(AAAI'18, [arXiv:1708.02072](https://arxiv.org/abs/1708.02072))의 정규화 지표 $\Omega_{base}, \Omega_{new}, \Omega_{all}$ — 오프라인(상한) 대비 비율로 retention을 표현. BWT와 함께 쓰면 "상한 대비 몇 %를 유지하나"를 읽을 수 있다.
- **한계**: BWT/FWT/Ω는 원래 **이미지/오디오 분류 정확도**용으로 정의됐다. 대화에서는 분류 정확도 대신 **시나리오별 judge 점수**로 재정의(re-grounding)해야 한다. 또 연속학습 논문은 깨끗한 task 경계와 많은 샘플을 가정하는데, **실제 대화 로그는 노이즈가 크고 양이 적다** — 추정 안정화에 필요한 최소 반복 횟수·재평가 주기는 열린 문제(§6).

---

## 5. 노하우 능동 최적화 효용 (프로젝트 고유 축)

> 사용자 요구: "노하우가 축적되고, **사용 횟수·경과 시간을 바탕으로 능동적 최적화**가 가능한 여지." 이 repo는 이미 `know_how`에 `use_count`·`last_used_at`을 두고 `score = recency × importance × similarity`로 재랭킹한다(Generative Agents 방식, [docs/know-how-memory.md](./know-how-memory.md)). 이를 **평가 가능한 지표**로 만든다.

직접 대응하는 단일 논문은 없으나, ExpeL의 insight 관리(UPVOTE/DOWNVOTE)와 Generative Agents 재랭킹이 근거다. 핵심은 **"자주·최근 쓰이는 노하우가 실제로 정답에 기여하는가"**를 ablation으로 검증하는 것.

### 5.1 노하우 효용 (ablation 기반)

```
Utility(m) = mean_over_cases( score(with m)  −  score(without m) )
```

특정 노하우 $m$을 검색 풀에서 제거하고 재실행했을 때의 평균 점수 하락. **양수가 클수록 가치 있는 노하우**, ≈0이면 무용, 음수면 유해(제거 대상).

### 5.2 능동 최적화 건전성 지표

| 지표 | 계산 | 읽는 법 |
|------|------|---------|
| **HitRate(m)** | (m이 정답 생성에 실제 기여한 턴) / (m이 주입된 턴) | 주입돼도 안 쓰이면 낮음 → 검색 노이즈 |
| **StaleRatio** | (`last_used_at` 오래됨 ∧ `importance` 낮음) 비율 | 높으면 pruning 후보 누적 |
| **PruneSafety** | pruning 전후 ACC 차이 | ≈0이면 안전한 최적화(용량↓, 정확도 유지) |
| **UseCount–Utility 상관** | corr(`use_count`, Utility) | 음/무상관이면 재랭킹이 가치 신호를 못 읽고 있음 |

- **로깅 필요**: 이미 있는 `use_count`/`last_used_at`/`importance`에 더해, **주입된 노하우가 답변에 실제 반영됐는지**(기여 플래그)와 ablation 재실행 점수.
- **능동 최적화의 정의**: `UseCount–Utility` 상관이 양수면 "자주 쓰는 노하우 = 가치 있는 노하우"가 성립 → `use_count`/`last_used_at` 기반 강화·감쇠가 정당. 음수면 재랭킹 공식이 잘못된 신호를 키우는 중이므로 공식 자체를 고쳐야 한다.

---

## 6. LLM-as-a-judge 신뢰성 (load-bearing 메타지표)

§2~5의 거의 모든 점수가 judge에서 나온다. **judge의 신뢰성이 전체 평가의 단일 최대 위험.** GPT-4o judge는 LongMemEval 기본값이고 사람 전문가와 높은 일치(부차 출처 기준 >97% 주장 — *정확한 수치는 medium-confidence*)를 보이지만, 다음을 반드시 통제한다.

- **사람 라벨 캘리브레이션**: held-out 사람 라벨 셋과 judge 점수의 일치도를 **Cohen's κ**로 측정·추적. 임계 이하면 judge를 신뢰하지 않는다.
- **알려진 편향**(MT-Bench / [arXiv:2306.05685](https://arxiv.org/abs/2306.05685)): 위치 편향(position), 장황함 편향(verbosity), 자기선호 편향(self-preference). 페어 비교 시 순서 무작위화, 길이 정규화 등으로 통제.
- **judge 안정성**: 이 repo의 `EvalJudge`는 `temperature=0` + structured output으로 **같은 입력에 같은 점수**를 강제한다(회귀 탐지에서 judge 흔들림이 모델 회귀로 오인되면 안 되므로) — 올바른 설계.
- **순수 자기판정 금지**(§3.2 재강조): 외부 신호 없는 self-judged 향상은 환상일 수 있다(Huang et al.).

---

## 7. 운영 로깅 스키마 (이 repo 매핑)

위 모든 지표를 실제 대화 로그에서 계산하기 위한 **최소 로깅 집합**(여러 검증 주장에서 도출한 엔지니어링 종합, medium-confidence):

```
턴(turn)마다:
  timestamp, sessionId, chatId
  scenarioId         # 안정적 시나리오/과제 키 — 시간에 걸쳐 재실행 (BWT/FWT, learning curve)
  trialIndex         # 같은 시나리오의 반복 회차 — 개선율
  userQuery          # 원본 입력
  memoryTypeLabel    # semantic/episodic + 5능력 태그 (PerLTQA·LongMemEval)
  retrievedCandidates: [ {knowHowId, similarity, rerankScore, contributed} ]  # recall@k, HitRate
  synthesizedAnswer  # 최종 응답
  verdict: {score, reasoning}   # EvalJudge 결과 (외부 신호 있을 때만 신뢰)
```

이 프로젝트에 **이미 있는 부품**과의 매핑:

| 지표 계열 | 재사용할 기존 코드 | 추가로 필요한 것 |
|-----------|-------------------|------------------|
| judge 채점 | `EvalJudge.kt` (temp=0, `JudgeVerdict`) | judge↔사람 κ 캘리브레이션 셋 |
| 고정 데이터셋·재실행 | `EvalDataset.kt` + `dataset.yaml`, `RegressionEvalTest.kt` | `scenarioId`/`trialIndex`/능력 태그, 개인화·성장 시나리오 |
| trace·score 저장 | `LangfuseClient.kt` (Langfuse Experiments) | run 간 BWT/FWT·learning curve 집계 뷰 |
| recall@k, 노하우 효용 | `ScoredKnowHow`, `know_how.use_count/last_used_at` | relevant-set 라벨, ablation 재실행, `contributed` 플래그 |

> 현재 `dataset.yaml`은 정적 사실 QA(`capital-france` 등)뿐이라 **성장·개인화를 측정하지 못한다**. 이 문서의 지표를 쓰려면 멀티세션·지식갱신·거부·반복노출 시나리오를 데이터셋에 추가하는 것이 첫 작업이다.

---

## 8. 도입 로드맵 (기존 하니스 확장)

1. **데이터셋 확장** → 검증: 5능력 태그가 달린 멀티세션 시나리오 N개가 `dataset.yaml`에 존재.
   - 정적 QA에 더해 (a) 선호 주입→회상, (b) 선호 변경→갱신, (c) 모르는 정보→거부, (d) 동일 상황 반복(`trialIndex`) 시나리오.
2. **judge 캘리브레이션** → 검증: held-out 사람 라벨 대비 Cohen's κ ≥ 목표치. 미달이면 rubric 보정.
3. **개인화 대시보드** → 검증: 5-way 정확도 + recall@k가 Langfuse run마다 분해 표시.
4. **성장 곡선** → 검증: `(scenarioId, trialIndex)` 재실행으로 ImprovementRate/LearningSlope 산출.
5. **망각 모니터** → 검증: 노하우 축적 전후 전체 시나리오 재채점으로 BWT 계산, BWT<0 알람.
6. **노하우 효용 ablation** → 검증: `use_count`–Utility 상관 산출 → 재랭킹 공식 검증/보정.

각 단계는 기존 `RegressionEvalTest`의 회귀 게이트(`mean ≥ threshold`)와 같은 패턴으로 임계값을 두어 **운영 게이트**로 승격할 수 있다.

---

## 9. 한계·주의 (검증 단계에서 드러난 것)

- **출처 등급**: 13개 종합 발견 중 11개가 1차 동료심사 논문(ICLR/ACL/NeurIPS/AAAI)에 대한 만장일치(3-0) 검증. 자가진화 서베이 1건은 medium(서베이라 정의 인용 출처가 [arXiv:2508.07407](https://arxiv.org/abs/2508.07407)로 정정됨), 로깅 스키마(§7)는 논문 발견이 아닌 엔지니어링 종합.
- **숫자 신뢰성**: Reflexion의 91% vs 80%(HumanEval)는 **저자 자체 baseline(80%)** 기준이고 OpenAI 공식치는 67%, 91%는 내부 단위테스트 신호 보강분 → "+11"은 저자 자체보고·외부보조 향상으로 인용. LongMemEval judge–사람 일치 ">97%"는 **부차 출처**라 정확 수치는 medium.
- **도메인 전이**: continual learning 지표는 분류 정확도용, 자가개선 프로토콜은 성공 신호 뚜렷한 과제용 — **열린 한국어/영어 대화로는 방법 템플릿만 차용**하고 절대 수치는 가정 금지.
- **반증된 주장(투명성)**: "LoCoMo가 human-vs-model gap을 핵심 평가 신호로 확립한다"는 1-2로 **반증**됨 → LoCoMo는 데이터셋·과제 설계 참고로만 쓰고 human-gap 프레이밍으로 지표를 세우지 말 것.

### 열린 질문

1. 이질적 지표(능력별 recall, BWT/FWT, 개선율, 일관성)를 **단일 합성 점수**로 묶을지 — 검증 출처 어디도 가중·집계법을 규정하지 않음. 다차원 대시보드가 합성보다 안전할 수 있다.
2. 노이즈 크고 양 적은 실제 대화 로그에서 BWT/FWT·learning curve가 통계적으로 안정되려면 **시나리오당 최소 반복 횟수·재평가 주기**가 얼마인가.
3. 열린·선호 중심 한국어/영어 도메인에서 judge를 어떻게 캘리브레이션하고, 1차 지표로 신뢰하기 위한 **κ 임계·편향 보정**은 무엇인가.
4. 귀납된 절차 노하우의 **품질(규칙이 맞고 일반화되는가)**에 대한 ground-truth를 어떻게 얻는가 — ExpeL은 추출 메커니즘만 보일 뿐 품질 채점 벤치마크는 없음.

---

## 10. 출처

**장기기억·개인화 벤치마크**
- LongMemEval (ICLR 2025) — [arXiv:2410.10813](https://arxiv.org/abs/2410.10813) · [repo](https://github.com/xiaowu0162/LongMemEval)
- LoCoMo (ACL 2024) — [arXiv:2402.17753](https://arxiv.org/abs/2402.17753) · [project](https://snap-research.github.io/locomo/)
- PerLTQA (SIGHAN/ACL 2024) — [arXiv:2402.16288](https://arxiv.org/abs/2402.16288)

**자가개선 에이전트**
- Reflexion (NeurIPS 2023) — [arXiv:2303.11366](https://arxiv.org/abs/2303.11366)
- Self-Refine (NeurIPS 2023) — [arXiv:2303.17651](https://arxiv.org/abs/2303.17651)
- ExpeL (AAAI 2024 Oral) — [arXiv:2308.10144](https://arxiv.org/abs/2308.10144)
- Voyager (skill library) — [arXiv:2305.16291](https://arxiv.org/abs/2305.16291)
- "LLMs Cannot Self-Correct Reasoning Yet" (ICLR 2024) — [arXiv:2310.01798](https://arxiv.org/abs/2310.01798)
- Self-Evolving Agents 서베이 — [arXiv:2507.21046](https://arxiv.org/abs/2507.21046) · [arXiv:2508.07407](https://arxiv.org/abs/2508.07407)

**연속학습 지표**
- GEM (NeurIPS 2017, ACC/BWT/FWT) — [arXiv:1706.08840](https://arxiv.org/abs/1706.08840)
- Kemker et al. (AAAI 2018, Ω 지표) — [arXiv:1708.02072](https://arxiv.org/abs/1708.02072)

**LLM-as-a-judge**
- MT-Bench / Judging LLM-as-a-Judge (NeurIPS 2023) — [arXiv:2306.05685](https://arxiv.org/abs/2306.05685)

> 리서치 통계: 5개 검색 각도 · 19개 소스 페치 · 81개 주장 추출 · 25개 적대적 검증(3표 중 2표 반증 시 폐기) · 24개 확정 · 1개 폐기.
