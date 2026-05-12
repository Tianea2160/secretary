package org.tianea.secretary.core.scheduling

import kotlinx.serialization.Serializable

/**
 * 스케줄 조회 결과 DTO. Koog list 도구의 반환 타입으로도 사용되므로 [Serializable].
 *
 * @property nextFireAtEpochMillis 다음 실행 예정 시각 (ms). 트리거가 만료/없을 경우 null.
 */
@Serializable
data class ScheduleInfo(
    val scheduleId: String,
    val name: String,
    val type: ScheduleType,
    val expression: String,
    val prompt: String,
    val chatId: Long,
    val nextFireAtEpochMillis: Long?,
)
