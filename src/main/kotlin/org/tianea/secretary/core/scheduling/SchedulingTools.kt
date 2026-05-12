package org.tianea.secretary.core.scheduling

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.currentCoroutineContext
import org.springframework.stereotype.Component

/**
 * Koog agent에 노출되는 스케줄링 도구 모음.
 *
 * 각 도구는 [ChatContext] 코루틴 엘리먼트를 통해 현재 chatId/sessionId를 가져온다.
 * UpdateRouter / AgentExecutionJob에서 agent.run 호출 전에 컨텍스트를 주입해야 한다.
 */
@Component
@LLMDescription("Schedule tasks (cron/interval) that re-invoke this assistant later with a stored prompt.")
class SchedulingTools(
    private val scheduleService: ScheduleService,
) : ToolSet {
    @Tool(customName = "register_cron_schedule")
    @LLMDescription(
        "Register a cron-based recurring schedule. " +
            "Uses Quartz 7-field cron format (seconds minutes hours day-of-month month day-of-week [year]), " +
            "e.g. '0 0 9 * * ?' = every day at 09:00:00. " +
            "When the schedule fires, the assistant re-runs with the given prompt. " +
            "Returns the generated scheduleId.",
    )
    suspend fun registerCronSchedule(
        @LLMDescription("Human-readable label, e.g. 'morning-briefing'. Used in list output and not for cancellation.")
        name: String,
        @LLMDescription("Quartz 7-field cron expression, e.g. '0 0 9 * * ?' for daily 09:00.")
        cron: String,
        @LLMDescription("Prompt sent to the assistant when the schedule fires.")
        prompt: String,
    ): String {
        val ctx = requireChatContext()
        return scheduleService.registerCron(
            chatId = ctx.chatId,
            sessionId = ctx.sessionId,
            name = name,
            cron = cron,
            prompt = prompt,
        )
    }

    @Tool(customName = "register_interval_schedule")
    @LLMDescription(
        "Register an interval-based recurring schedule that fires every N seconds, starting N seconds from now. " +
            "When the schedule fires, the assistant re-runs with the given prompt. " +
            "Returns the generated scheduleId.",
    )
    suspend fun registerIntervalSchedule(
        @LLMDescription("Human-readable label, e.g. 'health-check'. Used in list output and not for cancellation.")
        name: String,
        @LLMDescription("Interval in seconds between executions. Must be positive. First fire is intervalSeconds from now.")
        intervalSeconds: Long,
        @LLMDescription("Prompt sent to the assistant when the schedule fires.")
        prompt: String,
    ): String {
        val ctx = requireChatContext()
        return scheduleService.registerInterval(
            chatId = ctx.chatId,
            sessionId = ctx.sessionId,
            name = name,
            intervalSeconds = intervalSeconds,
            prompt = prompt,
        )
    }

    @Tool(customName = "cancel_schedule")
    @LLMDescription("Cancel a previously registered schedule by its scheduleId. Returns true if deleted, false if no such schedule.")
    suspend fun cancelSchedule(
        @LLMDescription("The scheduleId returned from register_cron_schedule or register_interval_schedule.")
        scheduleId: String,
    ): Boolean {
        val ctx = requireChatContext()
        return scheduleService.cancel(ctx.chatId, scheduleId)
    }

    @Tool(customName = "list_schedules")
    @LLMDescription("List all schedules registered in the current chat. Returns id, name, type, expression, prompt, and next fire time.")
    suspend fun listSchedules(): List<ScheduleInfo> {
        val ctx = requireChatContext()
        return scheduleService.listByChat(ctx.chatId)
    }

    private suspend fun requireChatContext(): ChatContext =
        currentCoroutineContext()[ChatContext]
            ?: error("ChatContext missing — agent.run must be invoked within ChatContext element")
}
