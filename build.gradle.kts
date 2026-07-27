import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    kotlin("plugin.serialization") version "2.1.20"
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // Ktor Server & WebSockets (with transitive conflicts excluded)
    val ktorVersion = "3.0.3"

    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        bundledPlugins("org.jetbrains.kotlin", "com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
}