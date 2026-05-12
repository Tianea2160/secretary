package org.tianea.secretary.core.scheduling

import org.quartz.spi.TriggerFiredBundle
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.quartz.SpringBeanJobFactory

/**
 * Quartz Job을 ApplicationContext에서 prototype 빈으로 조회하도록 JobFactory 커스터마이즈.
 *
 * Quartz 기본 동작은 no-arg 생성자로 Job을 newInstance — Spring 빈 주입 안 됨.
 * 이 팩토리는 jobClass에 해당하는 Spring 빈이 있으면 우선 조회하여 생성자 주입을 가능케 한다.
 * 빈이 없는 Job 클래스는 super 구현에 위임 (기존 동작 유지).
 */
@Configuration
class SpringQuartzConfig {
    @Bean
    fun springBeanJobFactory(applicationContext: ApplicationContext): SpringBeanJobFactory =
        object : SpringBeanJobFactory() {
            override fun createJobInstance(bundle: TriggerFiredBundle): Any {
                val jobClass = bundle.jobDetail.jobClass
                return runCatching { applicationContext.getBean(jobClass) }
                    .recoverCatching {
                        if (it is NoSuchBeanDefinitionException) super.createJobInstance(bundle) else throw it
                    }.getOrThrow()
            }
        }

    @Bean
    fun schedulerFactoryBeanCustomizer(jobFactory: SpringBeanJobFactory): SchedulerFactoryBeanCustomizer =
        SchedulerFactoryBeanCustomizer { factoryBean -> factoryBean.setJobFactory(jobFactory) }
}
