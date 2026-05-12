package org.tianea.secretary.core.scheduling

import kotlinx.serialization.Serializable

@Serializable
enum class ScheduleType {
    CRON,
    INTERVAL,
}
