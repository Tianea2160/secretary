package org.tianea.secretary.core.scheduling

import io.hypersistence.tsid.TSID
import org.quartz.CronExpression
import org.quartz.CronScheduleBuilder
import org.quartz.JobBuilder
import org.quartz.JobDataMap
import org.quartz.JobDetail
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.SimpleScheduleBuilder
import org.quartz.Trigger
import org.quartz.TriggerBuilder
import org.quartz.TriggerKey
import org.quartz.impl.matchers.GroupMatcher
import org.springframework.stereotype.Service

/**
 * Quartz [Scheduler]를 감싸 도메인 의미 (스케줄 등록/취소/조회) 단위로 노출.
 *
 * JobKey 명명 규칙:
 * - name = scheduleId (TSID 문자열)
 * - group = "chat-$chatId" — chat 단위 스코프. 사용자별 list가 group 매칭 한 번으로 끝남.
 */
@Service
class ScheduleService(
    private val scheduler: Scheduler,
) {
    fun registerCron(
        chatId: Long,
        sessionId: String,
        name: String,
        cron: String,
        prompt: String,
    ): String {
        require(CronExpression.isValidExpression(cron)) { "invalid cron expression: $cron" }
        val scheduleId = TSID.fast().toString()
        val jobDetail = buildJobDetail(scheduleId, chatId, sessionId, name, ScheduleType.CRON, cron, prompt)
        val trigger =
            triggerBuilder(scheduleId, chatId)
                .withSchedule(CronScheduleBuilder.cronSchedule(cron))
                .build()
        scheduler.scheduleJob(jobDetail, trigger)
        return scheduleId
    }

    fun registerInterval(
        chatId: Long,
        sessionId: String,
        name: String,
        intervalSeconds: Long,
        prompt: String,
    ): String {
        require(intervalSeconds > 0) { "intervalSeconds must be positive: $intervalSeconds" }
        val scheduleId = TSID.fast().toString()
        val jobDetail =
            buildJobDetail(
                scheduleId,
                chatId,
                sessionId,
                name,
                ScheduleType.INTERVAL,
                intervalSeconds.toString(),
                prompt,
            )
        val trigger =
            triggerBuilder(scheduleId, chatId)
                .withSchedule(
                    SimpleScheduleBuilder
                        .simpleSchedule()
                        .withIntervalInSeconds(intervalSeconds.toInt())
                        .repeatForever(),
                ).startAt(java.util.Date(System.currentTimeMillis() + intervalSeconds * 1000))
                .build()
        scheduler.scheduleJob(jobDetail, trigger)
        return scheduleId
    }

    fun cancel(
        chatId: Long,
        scheduleId: String,
    ): Boolean = scheduler.deleteJob(jobKey(scheduleId, chatId))

    fun listByChat(chatId: Long): List<ScheduleInfo> {
        val group = chatGroup(chatId)
        val keys = scheduler.getJobKeys(GroupMatcher.jobGroupEquals(group))
        return keys.map { key ->
            val detail = scheduler.getJobDetail(key)
            val data = detail.jobDataMap
            val triggers = scheduler.getTriggersOfJob(key)
            val nextFire = triggers.mapNotNull { it.nextFireTime }.minOrNull()?.time
            ScheduleInfo(
                scheduleId = data.getString(AgentExecutionJob.KEY_SCHEDULE_ID),
                name = data.getString(AgentExecutionJob.KEY_NAME),
                type = ScheduleType.valueOf(data.getString(AgentExecutionJob.KEY_TYPE)),
                expression = data.getString(AgentExecutionJob.KEY_EXPRESSION),
                prompt = data.getString(AgentExecutionJob.KEY_PROMPT),
                chatId = data.getLong(AgentExecutionJob.KEY_CHAT_ID),
                nextFireAtEpochMillis = nextFire,
            )
        }
    }

    private fun buildJobDetail(
        scheduleId: String,
        chatId: Long,
        sessionId: String,
        name: String,
        type: ScheduleType,
        expression: String,
        prompt: String,
    ): JobDetail {
        val data =
            JobDataMap().apply {
                put(AgentExecutionJob.KEY_SCHEDULE_ID, scheduleId)
                put(AgentExecutionJob.KEY_NAME, name)
                put(AgentExecutionJob.KEY_PROMPT, prompt)
                put(AgentExecutionJob.KEY_CHAT_ID, chatId)
                put(AgentExecutionJob.KEY_SESSION_ID, sessionId)
                put(AgentExecutionJob.KEY_TYPE, type.name)
                put(AgentExecutionJob.KEY_EXPRESSION, expression)
            }
        return JobBuilder
            .newJob(AgentExecutionJob::class.java)
            .withIdentity(jobKey(scheduleId, chatId))
            .usingJobData(data)
            .build()
    }

    private fun triggerBuilder(
        scheduleId: String,
        chatId: Long,
    ): TriggerBuilder<Trigger> =
        TriggerBuilder
            .newTrigger()
            .withIdentity(TriggerKey.triggerKey("trigger-$scheduleId", chatGroup(chatId)))

    private fun jobKey(
        scheduleId: String,
        chatId: Long,
    ): JobKey = JobKey.jobKey(scheduleId, chatGroup(chatId))

    private fun chatGroup(chatId: Long): String = "chat-$chatId"
}
