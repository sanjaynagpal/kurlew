package io.kurlew.pipeline.benchmarks

import kotlinx.coroutines.runBlocking

/**
 * Standalone runner for all benchmarks.
 * Can be run as a main function for comprehensive benchmark suite.
 */
fun main() = runBlocking {
    println("╔════════════════════════════════════════════════════════╗")
    println("║       Data Pipeline Performance Benchmarks            ║")
    println("╚════════════════════════════════════════════════════════╝")

    val benchmarks = PipelineBenchmarks()

    try {
        benchmarks.`benchmark - minimal pipeline overhead`()
        benchmarks.`benchmark - realistic pipeline throughput`()
        benchmarks.`benchmark - short-circuit optimization`()
        benchmarks.`benchmark - concurrent event processing`()
        benchmarks.`benchmark - latency percentiles`()
        benchmarks.`benchmark - error handling overhead`()
        benchmarks.`benchmark - memory allocation`()
        benchmarks.`benchmark - backpressure mechanism`()
        benchmarks.`benchmark - multiple interceptors overhead`()
        benchmarks.`benchmark - data enrichment overhead`()

        println("\n╔════════════════════════════════════════════════════════╗")
        println("║           All Benchmarks Completed Successfully       ║")
        println("╚════════════════════════════════════════════════════════╝")

    } catch (e: AssertionError) {
        println("\n❌ Benchmark assertion failed: ${e.message}")
        throw e
    } catch (e: Exception) {
        println("\n❌ Benchmark error: ${e.message}")
        throw e
    }
}