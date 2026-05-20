package org.tianea.secretary.core.scheduling

import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.scheduling.quartz.QuartzJobBean
import org.springframework.stereotype.Component
import org.tianea.secretary.core.agent.AssistantRunner
import org.tianea.secretary.telegram.TelegramMessageSender

/**
 * 트리거 시각에 발화: JobDataMap의 prompt로 agent를 다시 호출하고 결과를 원래 chat에 전송.
 *
 * Quartz 기본 JobFactory는 no-arg 생성자만 호출하므로 [SpringQuartzConfig]가 ApplicationContext에서
 * 이 빈을 조회한다 → 생성자 주입 가능. `prototype` scope으로 fire마다 새 인스턴스 발급.
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
class AgentExecutionJob(
    private val assistantRunner: AssistantRunner,
    private val messageSender: TelegramMessageSender,
) : QuartzJobBean() {
    override fun executeInternal(context: JobExecutionContext) {
        val data = context.mergedJobDataMap
        val scheduleId = data.getString(KEY_SCHEDULE_ID)
        val chatId = data.getLong(KEY_CHAT_ID)
        val sessionId = data.getString(KEY_SESSION_ID)
        val prompt = data.getString(KEY_PROMPT)

        log.info("Schedule fired scheduleId={} chatId={}", scheduleId, chatId)
        val reply =
            runCatching { assistantRunner.run(chatId, sessionId, prompt, messageId = null) }
                .onFailure { log.error("agent run failed scheduleId={}", scheduleId, it) }
                .getOrElse { "[schedule:$scheduleId] 실행 실패: ${it.message}" }
        messageSender.send(chatId, reply)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AgentExecutionJob::class.java)

        const val KEY_SCHEDULE_ID = "scheduleId"
        const val KEY_NAME = "name"
        const val KEY_PROMPT = "prompt"
        const val KEY_CHAT_ID = "chatId"
        const val KEY_SESSION_ID = "sessionId"
        const val KEY_TYPE = "type"
        const val KEY_EXPRESSION = "expression"
    }
}
