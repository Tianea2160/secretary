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
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

ktfmt { kotlinLangStyle() }

val enableNativeAccess = "--enable-native-access=ALL-UNNAMED"
val maxRamPercentage = "-XX:MaxRAMPercentage=60.0"
val unsafeAllow = "--sun-misc-unsafe-memory-access=allow"

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(enableNativeAccess, maxRamPercentage, unsafeAllow)
}

tasks.bootJar { manifest { attributes["Enable-Native-Access"] = "ALL-UNNAMED" } }

tasks.bootBuildImage {
    buildCache { bind { source.set("/tmp/cache-secretary.build") } }
    launchCache { bind { source.set("/tmp/cache-secretary.launch") } }
}
