plugins {
    id("java-library")
}

description = "Deterministic synthetic portfolio generator used for tests and benchmarks"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = libs.versions.java.get().toInt()
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

dependencies {
    api(project(":modules:domain"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencyLocking {
    lockAllConfigurations()
}