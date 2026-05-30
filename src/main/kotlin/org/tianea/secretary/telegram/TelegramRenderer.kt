package org.tianea.secretary.telegram

/**
 * Telegram 송신 텍스트의 단일 변환 stage.
 *
 * 구현체는 순수 함수처럼 동작해야 한다: 입력 [String]만 보고 동일 출력 [String]을 만들고 외부 상태(I/O, 로깅 외)를 건드리지 않는다.
 * [TelegramMessageSender]는 여러 stage를 순서대로 합성하므로 한 stage가 다음 stage가 이해 못 하는 마커를 남기면 파이프라인이 깨진다.
 *
 * 권장 규약:
 * - blank/empty 입력은 그대로 돌려준다(fast path).
 * - 같은 입력에 두 번 적용해도 같은 결과(idempotent)면 안전하지만 강제는 아니다.
 */
fun interface TelegramRenderer {
    fun render(message: String): String
}
