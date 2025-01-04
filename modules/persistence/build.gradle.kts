plugins {
    id("hedge.java-conventions")
}

description = "PostgreSQL persistence: schema migrations, connection pool and repositories"

dependencies {
    api(project(":modules:domain"))
    api(libs.micrometer.core)
    implementation(libs.hikari)
    implementation(libs.liquibase.core)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.postgresql)
    testImplementation(project(":modules:generator"))
}