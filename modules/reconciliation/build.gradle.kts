plugins {
    id("hedge.java-conventions")
}

description = "Reconciliation of positions across sources, lineage and deterministic replay"

dependencies {
    api(project(":modules:domain"))
    implementation(project(":modules:persistence"))

    testImplementation(project(":modules:generator"))
    testImplementation(libs.postgresql)
}
