# Secretary

Telegram 봇 인터페이스로 동작하는 AI 비서. Koog `AIAgent`와 Spring AI Google GenAI Starter를 함께 사용하는 듀얼 스택으로 Gemini / Ollama를 호출한다.

## 사전 요구사항

- JDK 25 (Gradle toolchain이 자동 프로비저닝, 로컬 설치 불필요)
- Docker Desktop (Compose v2 이상)
- Telegram bot token — [@BotFather](https://t.me/BotFather)
- Google API key — [Google AI Studio](https://aistudio.google.com/apikey)

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
| `GOOGLE_API_KEY` | Gemini 호출 키. Koog와 Spring AI 양쪽이 공유한다 |
| `TELEGRAM_BOT_TOKEN` | Telegram 봇 토큰 |
| `TELEGRAM_ALLOWED_CHAT_IDS` | 응답 허용 chat ID 콤마 목록 |
| `LANGFUSE_BASE_URL` / `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` | self-hosted Langfuse 트레이싱 (compose 기동 후 `http://localhost:3000`에서 키 발급) |

전체 변수·튜닝 옵션은 [docs/configuration.md](./docs/configuration.md).

## 문서

- 전체 시스템 흐름 → [docs/architecture.md](./docs/architecture.md)
- 환경변수·yaml·compose → [docs/configuration.md](./docs/configuration.md)
- 그 외 모든 기능별 문서 → [docs/](./docs/README.md)
