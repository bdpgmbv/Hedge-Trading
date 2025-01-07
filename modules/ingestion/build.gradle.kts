plugins {
    id("hedge.java-conventions")
}

description = "Ingestion of positions from custodians and market data from vendors"

dependencies {
    api(project(":modules:domain"))
    implementation(project(":modules:persistence"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.commons.csv)

    testImplementation(project(":modules:generator"))
    testImplementation(libs.postgresql)
}
