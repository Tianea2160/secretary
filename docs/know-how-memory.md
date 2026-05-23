# 노하우 메모리 — 절차적 지식 추출·축적·재사용

## 목적

사용자와의 대화에서 **재사용 가능한 절차적 노하우**("이런 상황에서는 이렇게 접근하면 된다")를 자동으로 추출해 축적하고, 이후 관련 작업 시작 시 관련 노하우만 골라 프롬프트에 주입한다.

- 단기기억(`ChatMemory`)이 "최근 N 메시지"의 대화 흐름을 다루고, 장기기억(`LongTermMemory`)이 "과거 원문 메시지"를 의미 검색한다면, 노하우 메모리는 **"재사용 가능한 방법론"**을 추출·정제·재사용하는 별개 계층이다.
- 저장 단위는 원문 메시지가 아닌 **요약된 절차 지식** — intent(언제 쓰는가)·body(구체적 방법)·importance(재사용 가치)·useCount(채택 횟수)·lastUsedAt(마지막 사용 시각) 등 자체 수명주기를 가진다.

## 설계

### 그래프 구조

```
nodeStart → reactStart → preprocess
  → retrieveKnowHow      (관련 노하우 top-k 검색 → 프롬프트 주입)
  → callLLM
  → emitText
  → reflect              (이번 턴에서 노하우 후보 추출)
  → consolidate          (기존 노하우와 비교 → ADD/UPDATE/SKIP)
  → reactEnd → nodeFinish
```

`reflect`·`consolidate`는 `emitText` 이후에 위치해 응답 텍스트가 이미 확정된 상태에서 실행된다. 두 노드 모두 **예외를 내부에서 격리**해 실패해도 사용자는 정상 응답을 받는다.

### 구성 요소

| 구성 요소 | 위치 | 역할 |
|-----------|------|------|
| `KnowHow` | `core/agent/knowhow/KnowHow.kt` | 절차적 노하우 도메인 모델 |
| `ScoredKnowHow` | `core/agent/knowhow/KnowHow.kt` | 유사도 + 재랭킹 점수가 붙은 검색 결과 |
| `KnowHowEntity` | `core/agent/knowhow/KnowHowEntity.kt` | `know_how` 테이블 JPA 엔티티 (`hibernate-vector` `vector(4096)` 매핑) |
| `KnowHowRepository` | `core/agent/knowhow/KnowHowRepository.kt` | Spring Data JPA 저장소 (CRUD + `cosine_distance` HQL 검색) |
| `KnowHowStore` | `core/agent/knowhow/KnowHowStore.kt` | 도메인↔엔티티 변환 + 임베딩 생성 + 재랭킹 facade |
| `KnowHowReflector` | `core/agent/knowhow/KnowHowReflector.kt` | 대화 턴 → 노하우 후보 추출 (detached LLM) |
| `KnowHowConsolidator` | `core/agent/knowhow/KnowHowConsolidator.kt` | ADD/UPDATE/SKIP 판정 후 저장소 반영 |
| `ChatStrategyConfig` | `core/agent/graph/ChatStrategyConfig.kt` | 그래프 노드 통합 |

### 데이터 모델

`know_how` 테이블 스키마:

```sql
CREATE TABLE know_how (
    id                text PRIMARY KEY,          -- TSID
    chat_id           bigint NOT NULL,           -- 사용자 격리 키
    intent            text NOT NULL,             -- "언제 쓰는가" 한 줄 요약 (임베딩 색인 기준)
    body              text NOT NULL,             -- 구체적 절차·방법론 본문
    embedding         vector(4096) NOT NULL,     -- intent 임베딩 (qwen3-embedding:8b)
    importance        double precision NOT NULL, -- 재사용 가치 (0.0~1.0)
    use_count         int NOT NULL DEFAULT 0,    -- retrieveKnowHow가 주입에 채택한 횟수
    created_at        timestamptz NOT NULL,
    last_used_at      timestamptz,               -- 마지막 주입 채택 시각 (재랭킹 recency 기준)
    source_session_id text NOT NULL              -- 추출 출처 세션 ID
);
```

raw 메시지 `vector_store`와 **별개 테이블**로 분리된다 — 노하우는 자체 메타데이터와 수명주기를 가지며, 두 컬렉션이 섞이면 retrieval 품질이 저하되기 때문이다.

## 동작 흐름

### retrieveKnowHow 노드

1. 사용자 입력 텍스트를 임베딩해 `know_how`에서 코사인 유사도 top-k 검색.
2. Generative Agents 방식으로 재랭킹: `score = recency × importance × similarity`.
   - recency: `lastUsedAt`(없으면 `createdAt`) 기준 지수 감쇠 (`halfLifeHours = 72`).
3. **토큰 예산** 이내의 상위 후보만 채택 (`token-budget: 1200`, body 글자 수 / 4 근사).
4. 채택된 노하우를 `"## 관련 노하우"` 마크다운 블록으로 user 메시지에 prepend.
5. `callLLM` 완료 후 `rewritePrompt`로 user 메시지를 원본 입력으로 되돌려 **ChatMemory에 노하우가 영속되지 않게** 한다.
6. 채택된 항목의 `useCount++`, `lastUsedAt` 갱신.

### reflect 노드

1. 현재 세션 프롬프트에서 마지막 user 메시지(원본 입력)와 응답 텍스트를 읽는다.
2. `KnowHowReflector.reflect()`를 호출 — **detached LLM 호출**로 대화 세션을 오염시키지 않는다.
3. structured output(`@Serializable ReflectResponse`)으로 0..N개 후보 `{intent, body, importance}` 추출.
4. `importance < min-importance(0.5)` 후보는 버린다.
5. 후보 리스트를 `ReflectOutput`(응답 텍스트 + 후보)에 담아 `consolidate`로 전달.
6. **모든 예외를 격리** — 실패 시 빈 리스트와 응답 텍스트를 그대로 통과.

### consolidate 노드

1. 각 후보에 대해 intent 임베딩으로 기존 노하우 유사 검색 (top-3).
2. 유사 항목이 없으면 즉시 ADD(신규 저장).
3. 유사 항목이 있으면 `KnowHowConsolidator.judgeVerdict()` — detached LLM이 Mem0 방식으로 판정:
   - **ADD**: 기존과 충분히 다름 → 신규 저장.
   - **UPDATE**: 기존 항목을 새 후보로 보완 → `store.update()`, 병합된 intent·body·importance 갱신.
   - **SKIP**: 중복 또는 저장 가치 없음 → 아무 작업 안 함.
4. 응답 텍스트를 그대로 통과. **모든 예외를 격리**.

## 설정

`application.yaml`:

```yaml
know-how:
  enabled: true                          # false 시 세 노드 모두 pass-through (on/off 스위치)
  retrieval:
    top-k: 5                             # 유사도 검색 상위 K
    token-budget: 1200                   # 주입 노하우 합산 근사 토큰 상한
  reflection:
    min-importance: 0.5                  # 이 미만 후보는 저장 안 함
```

## 알려진 제약

### 1. 인라인 추출로 인한 응답 지연

reflect·consolidate가 매 턴 1~2회 추가 LLM 호출을 발생시킨다. 응답 텍스트는 `emitText` 완료 후 그래프 종료(`nodeFinish`)까지 대기하므로 사용자 응답이 그만큼 지연된다.

latency가 문제가 되면 `docs/koog-strategy-graph.md` §2의 "응답 선전송" 옵션 참고.

### 2. ChatMemory 영속 차단 — rewritePrompt 의존

`retrieveKnowHow`에서 노하우를 user 메시지에 prepend한 뒤 `callLLM` 직후 `rewritePrompt`로 되돌린다. `SpringAiChatHistoryProvider`가 System/User/Assistant 텍스트 메시지를 영속하기 때문에, 이 되돌리기가 없으면 노하우 블록이 매 턴 ChatMemory에 누적된다. `rewritePrompt`가 실패하면 노하우 블록이 영속될 수 있어 모니터링 필요.

### 3. 4096차원 pgvector 인덱스 불가 — 풀스캔 운용

`docs/long-term-memory.md` §6과 동일 제약. 노하우 행 수는 raw 메시지보다 훨씬 적으므로 초기에는 충분하다. 데이터가 커지면 차원 축소(MRL) 또는 모델 교체를 검토.

### 4. Koog 0.8.0 `executeStructured` — 타입 파라미터는 reified

`reflect`·`consolidate`의 detached 호출은 `PromptExecutor.executeStructured<T>()` reified 버전을 사용한다. Koog 업그레이드 시 이 시그니처 변경 가능성이 있다. 업그레이드 전 `~/.gradle/caches/.../ai.koog/prompt-executor-model-jvm/` sources jar로 시그니처를 확인할 것.

### 5. enabled=false 시 retrieveKnowHow도 비활성

`know-how.enabled=false`로 설정하면 retrieve·reflect·consolidate 세 노드가 모두 pass-through가 된다. 노하우 주입 없이 순수 LLM 응답을 원할 때 사용.

## 출처

- [Voyager (skill library)](https://arxiv.org/abs/2305.16291) — 노하우를 코드 스킬로 축적하는 방식의 영감.
- [Generative Agents (reflection)](https://arxiv.org/abs/2304.03442) — recency × importance × relevance 재랭킹 방식.
- [Reflexion (verbal lesson)](https://arxiv.org/abs/2303.11366) — 실패 경험에서 교훈을 언어로 추출하는 루프.
- [Mem0 (ADD/UPDATE/DELETE/NOOP)](https://github.com/mem0ai/mem0) — 중복 판정 방식.
- [LangMem (memory 분류)](https://langchain-ai.github.io/langmem/) — semantic/episodic/procedural 분류 체계.
- [Koog issue #1001 (prompt flooding)](https://github.com/JetBrains/koog/issues/1001) — token budget 필요성 근거.
- 관련 코드: `core/agent/knowhow/`, `core/agent/graph/ChatStrategyConfig.kt`, `config/AgentConfig.kt`.
- 관련 문서: [docs/long-term-memory.md](./long-term-memory.md), [docs/chat-memory.md](./chat-memory.md), [docs/koog-strategy-graph.md](./koog-strategy-graph.md).
