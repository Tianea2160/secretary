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

// Override Spring Boot's pinned kotlinx.serialization version to match what Koog 0.8.0 was compiled against.
// Without this, Spring DM downgrades serialization-core to 1.6.3 while Koog ships serialization-json-io 1.8.1,
// causing AbstractMethodError at runtime.
extra["kotlin-serialization.version"] =
    libs.versions.kotlinx.serialization
        .get()

dependencies {
    implementation(platform(libs.spring.ai.bom))
    implementation(platform(libs.kotlinx.coroutines.bom))

    implementation(libs.spring.boot.starter)
    implementation(libs.kotlin.reflect)

    implementation(libs.koog.agents)
    implementation(libs.koog.spring.ai.starter.model.chat)
    implementation(libs.spring.ai.starter.model.google.genai)

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
