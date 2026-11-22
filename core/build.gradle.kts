import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Dependency-free self-test (simulated ELM327 + CX-5 PCM). `./gradlew :core:selfTest`
tasks.register<JavaExec>("selfTest") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("uk.co.ashunt.carmonitor.core.SelfTest")
    workingDir = rootDir
}
tasks.named("check") { dependsOn("selfTest") }
