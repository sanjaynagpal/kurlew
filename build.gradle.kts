plugins {
    kotlin("jvm") version "2.2.21"
    application
}

group = "io.kurlew"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor Pipeline utilities
    implementation("io.ktor:ktor-utils:2.3.7")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // JUnit 5 for benchmarks
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
}

kotlin {
    jvmToolchain(24)
}

tasks.test {
    useJUnitPlatform()

    // JVM args for performance testing
    jvmArgs(
        "-Xms2g",
        "-Xmx4g",
        "-XX:+UseG1GC"
    )

    // Separate benchmark tests
//    if (project.hasProperty("benchmark")) {
//        include("**/benchmarks/**")
//
//        // More time for benchmarks
//        testLogging {
//            events("passed", "skipped", "failed")
//            showStandardStreams = true
//        }
//    } else {
//        // Exclude benchmarks from regular test runs
//        exclude("**/benchmarks/**")
//    }
}

// Task to run only benchmarks
tasks.register<Test>("benchmark") {
    description = "Run performance benchmarks"
    group = "verification"

    useJUnitPlatform()
    include("**/benchmarks/**")

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // Optimized JVM settings for benchmarks
    jvmArgs(
        "-Xms4g",
        "-Xmx4g",
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=50",
        "-server"
    )

    // Give benchmarks more time
    maxHeapSize = "4g"
}

// Application plugin configuration for running examples
application {
    mainClass.set("io.kurlew.examples.SimpleDataPipelineExampleKt")
}

// Task to run benchmark runner
tasks.register<JavaExec>("runBenchmarks") {
    description = "Run all benchmarks via BenchmarkRunner"
    group = "verification"

    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.kurlew.pipeline.benchmarks.BenchmarkRunnerKt")

    jvmArgs(
        "-Xms4g",
        "-Xmx4g",
        "-XX:+UseG1GC",
        "-server"
    )
}

// Task to run examples
tasks.register<JavaExec>("runSimpleExample") {
    description = "Run simple data pipeline example"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.kurlew.examples.SimpleDataPipelineExampleKt")
}

tasks.register<JavaExec>("runWebSocketExample") {
    description = "Run WebSocket streaming example"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.kurlew.examples.WebSocketStreamingExampleKt")
}

tasks.register<JavaExec>("runPersistenceExample") {
    description = "Run persistence with retry example"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.kurlew.examples.PersistenceWithRetryExampleKt")
}

// Generate benchmark report
tasks.register("benchmarkReport") {
    description = "Run benchmarks and generate report"
    group = "verification"

    dependsOn("benchmark")

    doLast {
        println("""
            
            ╔════════════════════════════════════════════════════════╗
            ║     Benchmark Report Generated Successfully           ║
            ║                                                        ║
            ║  See: docs/benchmark-report.md for detailed analysis  ║
            ╚════════════════════════════════════════════════════════╝
        """.trimIndent())
    }
}