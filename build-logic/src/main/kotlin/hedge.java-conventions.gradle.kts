import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.getByType

plugins {
    id("java-library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javaVersion = libs.findVersion("java").get().requiredVersion

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion.toInt()
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj-core").get())
    "testImplementation"(libs.findLibrary("archunit-junit5").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

dependencyLocking {
    lockAllConfigurations()
}