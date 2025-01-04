plugins {
    id("hedge.java-conventions")
    alias(libs.plugins.spring.boot)
}

description = "HTTP API for reading portfolio state"

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":modules:persistence"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(project(":modules:generator"))
}