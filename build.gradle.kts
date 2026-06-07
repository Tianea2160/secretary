plugins {
    `version-catalog`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.org.springframework.boot)
    alias(libs.plugins.io.spring.dependency.management)
    alias(libs.plugins.ktfmt)
}

group = "org.tianea"

version = "0.0.1-SNAPSHOT"

description = "secretary"

extra["kotlin-serialization.version"] = libs.versions.kotlinx.serialization.get()

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

repositories { mavenCentral() }

dependencies {
    implementation(platform(libs.spring.ai.bom))

    implementation(libs.spring.boot.starter)
    implementation(libs.kotlin.reflect)

    implementation(libs.koog.agents)
    implementation(libs.koog.longterm.memory)
    implementation(libs.koog.spring.ai.starter.model.chat)
    implementation(libs.koog.spring.ai.starter.model.embedding)
    implementation(libs.koog.spring.ai.starter.chat.memory)
    implementation(libs.koog.spring.ai.starter.vector.store)
    implementation(libs.spring.ai.starter.model.ollama)
    implementation(libs.spring.ai.starter.chat.memory.jdbc)
    implementation(libs.spring.ai.starter.vector.store.pgvector)
    implementation(libs.telegrambots.springboot.longpolling.starter)
    implementation(libs.telegrambots.client)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.hibernate.vector)
    implementation(libs.spring.boot.starter.quartz)
    implementation(libs.hypersistence.tsid)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

ktfmt { kotlinLangStyle() }

val enableNativeAccess = "--enable-native-access=ALL-UNNAMED"
val maxRamPercentage = "-XX:MaxRAMPercentage=60.0"
val unsafeAllow = "--sun-misc-unsafe-memory-access=allow"

/**
 * `.env` 파일을 파싱해 key-value 맵으로 반환한다.
 *
 * `@EnabledIfEnvironmentVariable`·`System.getenv()`는 실제 프로세스 환경변수만 읽고 `.env`를 자동 로드하지 않으므로, 테스트
 * JVM에 직접 주입하기 위해 사용한다. 빈 줄·`#` 주석은 건너뛰고, `=` 기준으로 한 번만 분리하며, 값을 감싼 따옴표(`"`/`'`)는 제거한다.
 *
 * @return `.env`가 없으면 빈 맵
 */
fun loadEnv(): Map<String, String> =
    file(".env")
        .takeIf { it.exists() }
        ?.readLines()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
        ?.associate { line ->
            val (key, value) = line.split("=", limit = 2)
            key.trim() to value.trim().removeSurrounding("\"").removeSurrounding("'")
        }
        .orEmpty()

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    environment(loadEnv())
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(enableNativeAccess, maxRamPercentage, unsafeAllow)
}

tasks.bootJar { manifest { attributes["Enable-Native-Access"] = "ALL-UNNAMED" } }

tasks.bootBuildImage {
    buildCache { bind { source.set("/tmp/cache-secretary.build") } }
    launchCache { bind { source.set("/tmp/cache-secretary.launch") } }
}
