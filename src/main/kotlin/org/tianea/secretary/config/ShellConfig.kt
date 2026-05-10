package org.tianea.secretary.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.shell.core.autoconfigure.TerminalCustomizer
import java.nio.charset.StandardCharsets

@Configuration
class ShellConfig {
    @Bean
    fun utf8TerminalCustomizer(): TerminalCustomizer =
        TerminalCustomizer { builder ->
            builder.encoding(StandardCharsets.UTF_8)
        }
}
