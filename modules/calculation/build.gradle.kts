plugins {
    id("hedge.java-conventions")
}

description = "Authoritative FX exposure and hedge calculation, with its JMH baseline"

val jmh by sourceSets.creating

dependencies {
    api(project(":modules:domain"))

    testImplementation(project(":modules:generator"))
    testImplementation(libs.jqwik)

    "jmhImplementation"(project(":modules:domain"))
    "jmhImplementation"(project(":modules:generator"))
    "jmhImplementation"(sourceSets["main"].output)
    "jmhImplementation"(libs.jmh.core)
    "jmhAnnotationProcessor"(libs.jmh.generator.annprocess)
}

tasks.named<JavaCompile>("compileJmhJava") {
    options.compilerArgs = listOf("-parameters")
}

tasks.register<JavaExec>("jmh") {
    // A local of this block, never a script-level property: a doFirst that closes over a script
    // property drags the build script itself into the configuration cache, which cannot serialize it.
    val reportDirectory = layout.buildDirectory.dir("reports/jmh")

    group = "verification"
    description = "Runs the Layer 8 baseline benchmarks"
    mainClass = "org.openjdk.jmh.Main"
    classpath = jmh.runtimeClasspath
    outputs.dir(reportDirectory)
    // JMH refuses to start if it cannot touch its result file, and it will not create the
    // directory itself, so the directory has to exist before the benchmark JVM is forked.
    doFirst {
        reportDirectory.get().asFile.mkdirs()
    }
    args(
        "-rf", "json",
        "-rff", reportDirectory.get().file("baseline.json").asFile.absolutePath,
    )
}