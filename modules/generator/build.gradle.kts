plugins {
    id("hedge.java-conventions")
}

description = "Deterministic synthetic portfolio generator used for tests and benchmarks"

dependencies {
    api(project(":modules:domain"))
}