package org.tianea.secretary

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication @ConfigurationPropertiesScan class SecretaryApplication

fun main(args: Array<String>) {
    runApplication<SecretaryApplication>(*args)
}
