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
    // Test only: the replay invariant is checked by comparing two lists of positions,
    // which the reconciliation engine already does. Main code must not reach for it.
    testImplementation(project(":modules:reconciliation"))
    testImplementation(libs.postgresql)
}
