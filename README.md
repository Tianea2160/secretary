# Secretary

Telegram 봇 인터페이스로 동작하는 AI 비서. Koog `AIAgent`(에이전트 워크플로)와 Spring AI(모델·메모리 인프라)를 계층으로 결합하며, chat·embedding 모두 로컬 Ollama로 호출한다.

## 사전 요구사항

- JDK 25 (Gradle toolchain이 자동 프로비저닝, 로컬 설치 불필요)
- Docker Desktop (Compose v2 이상)
- [Ollama](https://ollama.com) — `localhost:11434`에 `qwen3:4b-instruct-2507-q4_K_M`(chat)·`qwen3-embedding:8b`(embedding) 필요
- Telegram bot token — [@BotFather](https://t.me/BotFather)

## Quickstart

```bash
cp .env.example .env && $EDITOR .env    # 키 채우기 (아래 표 참고)
docker compose up -d                    # Postgres + Langfuse 스택 기동
./gradlew bootRun                       # 애플리케이션 실행
```

기동 후 Telegram에서 봇과 대화한다. `TELEGRAM_ALLOWED_CHAT_IDS`에 본인 chat ID를 등록해야 응답한다.

## 필수 환경변수

| 이름 | 용도 |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Telegram 봇 토큰 |
| `TELEGRAM_ALLOWED_CHAT_IDS` | 응답 허용 chat ID 콤마 목록 |
| `LANGFUSE_BASE_URL` / `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` | self-hosted Langfuse 트레이싱 (compose 기동 후 `http://localhost:3000`에서 키 발급) |

전체 변수·튜닝 옵션은 [docs/configuration.md](./docs/configuration.md).

## 노하우 우선순위 (가중치)

노하우를 프롬프트에 주입할 때, 4개 요소의 **가중합**으로 점수를 매겨 상위 후보를 고른다:

```
score = w_sim·similarity + w_rec·recency + w_imp·importance + w_freq·frequency
```

가중치는 요소의 **결정성**으로 나뉜다:

| 요소 | 의미 | 분류 |
|---|---|---|
| `similarity` | 질의 적합도(임베딩 코사인) | 결정적 (AI 임베딩이지만 재현 가능) |
| `recency` | 최근 사용 시각 기준 지수 감쇠 | **결정적** (사용 통계) |
| `frequency` | 사용 횟수(`use_count`) 포화 정규화 | **결정적** (사용 통계) |
| `importance` | LLM이 매긴 재사용 가치 | **비결정적** (모델 판단, 실행마다 흔들릴 수 있음) |

- `use_count`가 많을수록 `frequency`가 커져 상위로 올라가고, 마지막 사용이 오래될수록 `recency`가 감쇠해 내려간다.
- 모든 가중치·반감기·포화상수는 `application.yaml`의 `know-how.rerank.*`에서 튜닝한다(기본 가중치 각 0.25). relevance를 게이트처럼 강하게 두려면 `weight-similarity`를 높인다.
- 상세: [docs/know-how-memory.md](./docs/know-how-memory.md).

## 문서

- 전체 시스템 흐름 → [docs/architecture.md](./docs/architecture.md)
- 환경변수·yaml·compose → [docs/configuration.md](./docs/configuration.md)
- 그 외 모든 기능별 문서 → [docs/](./docs/README.md)
