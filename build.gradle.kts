plugins {
    `version-catalog`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.org.springframework.boot)
    alias(libs.plugins.io.spring.dependency.management)
}

group = "org.tianea"
version = "0.0.1-SNAPSHOT"
description = "secretary"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["kotlin-serialization.version"] =
    libs.versions.kotlinx.serialization
        .get()
extra["kotlin-coroutines.version"] =
    libs.versions.kotlinx.coroutines
        .get()

dependencies {
    implementation(platform(libs.spring.ai.bom))
    implementation(platform(libs.kotlinx.coroutines.bom))

    implementation(libs.spring.boot.starter)
    implementation(libs.kotlin.reflect)

    implementation(libs.koog.agents)
    implementation(libs.koog.spring.ai.starter.model.chat)
    implementation(libs.koog.spring.ai.starter.model.embedding)
    implementation(libs.koog.spring.ai.starter.chat.memory)
    implementation(libs.koog.spring.ai.starter.vector.store)
    implementation(libs.spring.ai.starter.model.google.genai)
    implementation(libs.spring.ai.starter.model.ollama)
    implementation(libs.spring.ai.starter.chat.memory.jdbc)
    implementation(libs.spring.ai.starter.vector.store.pgvector)
    implementation(libs.spring.shell.starter)
    implementation(libs.spring.shell.starter.ffm)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.hypersistence.tsid)
    runtimeOnly(libs.postgresql)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.core.jvm)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxParallelForks = 4
}

tasks.bootJar {
    manifest {
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
}

tasks.bootRun {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
