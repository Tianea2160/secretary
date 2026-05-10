---
paths:
  - "src/**/*.kt"
---

# Kotlin 코드 스타일

- **Kotlin 컴파일러 옵션**: `-Xjsr305=strict` + `-Xannotation-default-target=param-property` (`build.gradle.kts:60-63`)
  - JSR305 nullability 어노테이션이 strict로 처리됨 — 외부 Java API의 `@Nullable`/`@NonNull` 무시 시 컴파일 에러
  - `param-property` — 생성자 파라미터의 어노테이션이 자동으로 property에 함께 적용
- **의존성 BOM**: `spring-ai-bom:1.1.6`, `kotlinx-coroutines-bom:1.11.0-rc02` — Spring AI / Coroutines 모듈 버전 직접 지정 금지.
- **버전 카탈로그**: 새 의존성 추가 시 `gradle/libs.versions.toml`에 먼저 등록한 뒤 `build.gradle.kts`에서 `libs.xxx` 참조.
- **kotlinx-serialization 버전 다운그레이드 회피**: `build.gradle.kts:23-31`에서 `extra["kotlin-serialization.version"]`로 강제 오버라이드. Spring DM이 1.6.3으로 다운그레이드하면 Koog 0.8.0과 `AbstractMethodError` 발생.

## 주석 스타일

- **`//` 라인 주석 사용 금지**. 메서드/클래스 설명은 KDoc(`/** ... */`)으로 작성 — IDE 추론·문서 생성 활용 + 가독성 ↑.
- KDoc 표준 태그 사용: `@param`, `@return`, `@throws`, `@see`, `@property`.
- 코드 안 narrative 주석("이 줄은 X를 한다")은 작성하지 말 것 — 잘 명명된 함수/변수가 이미 WHAT을 설명한다. 함수 분리 또는 명명 개선으로 대체.
- 진짜 비명시적 WHY(외부 제약, 함정, 워크어라운드 이유)는 KDoc 본문에 한 줄로 — 또는 클래스/메서드 KDoc의 별도 섹션.
