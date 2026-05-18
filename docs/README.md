# 문서

Secretary 프로젝트의 기술 문서 인덱스. 루트 [README.md](../README.md)는 5분 셋업만 다루고, 그 외 모든 설명은 여기서 시작한다.

## 시작점

- 시스템이 어떻게 동작하는지 → [architecture.md](./architecture.md)
- 환경변수·yaml·docker-compose → [configuration.md](./configuration.md)
- 두 AI 스택(Koog vs Spring AI) 중 어디에 기능을 붙일지 → [koog-vs-spring-ai.md](./koog-vs-spring-ai.md)

## 기능별

- [chat-memory.md](./chat-memory.md) — 세션별 단기기억. Koog `ChatMemory` + Spring AI `JdbcChatMemoryRepository` 브릿지.
- [long-term-memory.md](./long-term-memory.md) — PgVector 기반 장기기억. 4096차원 임베딩, top-k retrieval.
- [koog-strategy-graph.md](./koog-strategy-graph.md) — Koog `strategy { }` DSL 사용법 (선형 그래프 + 확장 패턴).
- [tui-ffm.md](./tui-ffm.md) — Spring Shell + JLine FFM 터미널 백엔드.

## 진행 중

- [plans/chat-tui.md](./plans/chat-tui.md) — Spring Shell TUI 채팅 클라이언트 plan.

## 새 문서를 추가할 때

기존 문서들이 공유하는 골격:

- 본문 한국어, 코드 식별자·env·yaml 키는 영문 원문.
- 섹션 순서: `## 목적` → `## 설계 — <부제>` → `## 동작 흐름` / `## 구현 요소` → `## 알려진 제약` → `## 출처`.
- 흐름·구조는 ASCII flowchart, 코드 블록은 언어 지정 필수, 표는 비교가 있을 때만.
- cross-link은 모두 상대 경로(`./xxx.md`, `../README.md`).
- 같은 사실은 한 문서에만 둔다 — env는 [configuration.md](./configuration.md), 스택 비교는 [koog-vs-spring-ai.md](./koog-vs-spring-ai.md).
