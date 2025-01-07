plugins {
    id("hedge.java-conventions")
    alias(libs.plugins.spring.boot)
}

description = "Watches for delivered custodian and vendor artifacts and ingests them"

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":modules:ingestion"))
    implementation(project(":modules:reconciliation"))
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
}
