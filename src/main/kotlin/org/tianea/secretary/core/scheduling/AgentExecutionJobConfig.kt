package org.tianea.secretary.core.scheduling

import org.quartz.CronScheduleBuilder
import org.quartz.JobBuilder
import org.quartz.JobDetail
import org.quartz.Trigger
import org.quartz.TriggerBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Quartz `JobDetail` / `Trigger` 빈 정의.
 *
 * Spring Boot `QuartzAutoConfiguration`이 이 두 빈을 감지해 `Scheduler`에 자동 등록한다.
 * 따라서 `Scheduler.scheduleJob()`을 수동으로 호출하면 JDBC job store에서
 * `ObjectAlreadyExistsException`이 발생하므로 직접 등록하지 말 것.
 *
 * @property AgentExecutionJob 실행 단위 — `SpringBeanJobFactory`가 `ApplicationContext`에서 조회한다.
 *
 * `storeDurably()` — `Trigger`가 모두 제거되어도 `JobDetail`이 JDBC store에 유지되도록 한다.
 * `withMisfireHandlingInstructionDoNothing()` — 1초 cron에서 누락분을 보충 실행하지 않고 다음 주기를 따른다.
 */
@Configuration
class AgentExecutionJobConfig {
    @Bean
    fun agentExecutionJobDetail(): JobDetail =
        JobBuilder
            .newJob(AgentExecutionJob::class.java)
            .withIdentity("agent-execution-job")
            .storeDurably()
            .build()

    @Bean
    fun agentExecutionJobTrigger(agentExecutionJobDetail: JobDetail): Trigger =
        TriggerBuilder
            .newTrigger()
            .forJob(agentExecutionJobDetail)
            .withIdentity("agent-execution-job-trigger")
            .withSchedule(
                CronScheduleBuilder
                    .cronSchedule("* * * * * ?")
                    .withMisfireHandlingInstructionDoNothing(),
            ).build()
}
