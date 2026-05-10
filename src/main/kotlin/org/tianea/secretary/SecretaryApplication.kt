package org.tianea.secretary

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.io.File

@SpringBootApplication
class SecretaryApplication

fun main(args: Array<String>) {
    loadDotenvIntoSystemProperties()
    System.setProperty("org.jline.terminal.provider", System.getProperty("JLINE_TERMINAL_PROVIDER", "ffm"))

    runApplication<SecretaryApplication>(*args)
}

private fun loadDotenvIntoSystemProperties(file: File = File(".env")) {
    if (!file.exists()) return
    file.useLines { lines ->
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim()
            val value =
                line
                    .substring(idx + 1)
                    .trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
            // 이미 set된 system property나 -D JVM 인자는 .env가 덮어쓰지 않는다.
            if (System.getProperty(key) == null) {
                System.setProperty(key, value)
            }
        }
    }
}
