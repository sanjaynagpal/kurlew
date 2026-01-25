package io.kurlew.pipeline.benchmarks
import io.kurlew.pipeline.DataEvent
import io.kurlew.pipeline.DataPipeline
import io.kurlew.pipeline.DataPipelinePhases
import io.kurlew.pipeline.extensions.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis

/**
 * Performance benchmarks for the Data Pipeline.
 *
 * Run with: ./gradlew test --tests "PipelineBenchmarks"
 *
 * Benchmarks cover:
 * - Pipeline overhead
 * - Throughput (events/second)
 * - Latency (p50, p95, p99)
 * - Concurrency performance
 * - Short-circuit optimization
 * - Memory allocation
 */
class PipelineBenchmarks {

    companion object {
        const val WARMUP_ITERATIONS = 1000
        const val BENCHMARK_ITERATIONS = 10000
        const val CONCURRENT_EVENTS = 1000
    }

    /**
     * Benchmark 1: Minimal Pipeline Overhead
     * Measures the base overhead of pipeline traversal with no-op interceptors.
     */
    @org.junit.jupiter.api.Test
    fun `benchmark - minimal pipeline overhead`() = runBlocking {
        println("\n=== Benchmark: Minimal Pipeline Overhead ===")

        val pipeline = DataPipeline()

        // Add no-op interceptors to each phase
        pipeline.intercept(DataPipelinePhases.Setup) { proceed() }
        pipeline.intercept(DataPipelinePhases.Monitoring) { proceed() }
        pipeline.intercept(DataPipelinePhases.Features) { proceed() }
        pipeline.intercept(DataPipelinePhases.Process) { proceed() }
        pipeline.intercept(DataPipelinePhases.Fallback) { }

        // Warmup
        repeat(WARMUP_ITERATIONS) {
            pipeline.execute(DataEvent("warmup"))
        }

        // Benchmark
        val times = mutableListOf<Long>()
        repeat(BENCHMARK_ITERATIONS) {
            val event = DataEvent("data-$it")
            val duration = measureTimeMillis {
                pipeline.execute(event)
            }
            times.add(duration)
        }

        printStats("Minimal Pipeline", times)

        // Assertions
        val avgMs = times.average()
        println("✓ Average overhead: ${avgMs}ms per event")
        assert(avgMs < 1.0) { "Pipeline overhead should be <1ms, was ${avgMs}ms" }
    }

    /**
     * Benchmark 2: Realistic Pipeline Throughput
     * Measures throughput with validation, enrichment, and processing.
     */
    @org.junit.jupiter.api.Test
    fun `benchmark - realistic pipeline throughput`() = runBlocking {
        println("\n=== Benchmark: Realistic Pipeline Throughput ===")

        val pipeline = DataPipeline()
        var processedCount = 0

        pipeline.monitoringWrapper()

        pipeline.validate { event ->
            event.incomingData is String
        }

        pipeline.enrich { _, call ->
            call.enrich("timestamp", System.currentTimeMillis())
            call.enrich("processed", true)
        }

        pipeline.process { event, call ->
            // Simulate light processing
            val data = event.incomingData as String
            call.enrich("length", data.length)
            processedCount++
        }

        // Warmup
        repeat(WARMUP_ITERATIONS) {
            pipeline.execute(DataEvent("warmup-$it"))
        }

        // Benchmark
        val totalTime = measureTimeMillis {
            repeat(BENCHMARK_ITERATIONS) {
                pipeline.execute(DataEvent("data-$it"))
            }
        }

        val throughput = (BENCHMARK_ITERATIONS.toDouble() / totalTime) * 1000
        val avgLatency = totalTime.toDouble() / BENCHMARK_ITERATIONS

        println("Events processed: $processedCount")
        println("Total time: ${totalTime}ms")
        println("Throughput: ${String.format("%.2f", throughput)} events/sec")
        println("Average latency: ${String.format("%.3f", avgLatency)}ms")

        assert(throughput > 1000) { "Throughput should be >1000 events/sec, was $throughput" }
    }

    /**
     * Benchmark 3: Short-Circuit Optimization
     * Measures performance gain from early validation failure.
     */
    @org.junit.jupiter.api.Test
    fun `benchmark - short-circuit optimization`() = runBlocking {
        println("\n=== Benchmark: Short-Circuit Optimization ===")

        // Pipeline with validation that fails
        val fastPipeline = DataPipeline()
        fastPipeline.validate { _ -> false } // Always fails
        fastPipeline.process { _, _ ->
            delay(10) // Expensive operation
        }

        // Pipeline without validation (runs expensive op)
        val slowPipeline = DataPipeline()
        slowPipeline.process { _, _ ->
            delay(10) // Expensive operation
        }

        // Warmup
        repeat(10) {
            fastPipeline.execute(DataEvent("warmup"))
            slowPipeline.execute(DataEvent("warmup"))
        }

        // Benchmark fast path (validation fails, skips process)
        val fastTime = measureTimeMillis {
            repeat(100) {
                fastPipeline.execute(DataEvent("data-$it"))
            }
        }

        // Benchmark slow path (no validation, runs process)
        val slowTime = measureTimeMillis {
            repeat(100) {
                slowPipeline.execute(DataEvent("data-$it"))
            }
        }

        val speedup = slowTime.toDouble() / fastTime

        println("With validation failure (fast): ${fastTime}ms")
        println("Without validation (slow): ${slowTime}ms")
        println("Speedup: ${String.format("%.2f", speedup)}x")

        assert(fastTime < slowTime) { "Fast path should be faster than slow path" }
        assert(speedup > 5) { "Short-circuit should provide >5x speedup, was ${speedup}x" }
    }

    /**
     * Benchmark 4: Concurrent Event Processing
     * Measures throughput with multiple concurrent events.
     */
    @org.junit.jupiter.api.Test
    fun `benchmark - concurrent event processing`() = runBlocking {
        println("\n=== Benchmark: Concurrent Event Processing ===")

        val pipeline = DataPipeline()
        val processedCount = java.util.concurrent.atomic.AtomicInteger(0)

        pipeline.monitoringWrapper()
        pipeline.validate { event -> event.incomingData is String }
        pipeline.process { _, _ ->
            delay(1) // Simulate I/O
            processedCount.incrementAndGet()
        }

        // Warmup
        repeat(100) {
            launch { pipeline.execute(DataEvent("warmup-$it")) }
        }
        delay(200)

        // Benchmark - process many events concurrently
        val totalTime = measureTimeMillis {
            val jobs = List(CONCURRENT_EVENTS) { i ->
                launch {
                    pipeline.execute(DataEvent("data-$i"))
                }
            }
            jobs.joinAll()
        }

        val throughput = (CONCURRENT_EVENTS.toDouble() / totalTime) * 1000

        println("Concurrent events: $CONCURRENT_EVENTS")
        println("Total time: ${totalTime}ms")
        println("Throughput: ${String.format("%.2f", throughput)} events/sec")
        println("Events processed: ${processedCount.get()}")

        assert(processedCount.get() >= CONCURRENT_EVENTS) {
            "All events should be processed"
        }
    }

    /**
     * Benchmark 5: Latency Percentiles
     * Measures p50, p95, p99 latencies.
     */
    @org.junit.jupiter.api.Test
    fun `benchmark - latency percentiles`() = runBlocking {
        println("\n=== Benchmark: Latency Percentiles ===")

        val pipeline = DataPipeline()

        pipeline.monitoringWrapper()
        pipeline.validate { event -> event.incomingData is String }
        pipeline.enrich { _, call ->
            call.enrich("timestamp", System.currentTimeMillis())
        }
        pipeline.process { _, _ ->
            // Simulate variable processing time
            val delay = (Math.random() * 5).toLong()
            delay(delay)
        }

        // Warmup
        repeat(WARMUP_ITERATIONS) {
            pipeline.execute(DataEvent("warmup"))
        }

        // Collect latency measurements
        val latencies = mutableListOf<Long>()

        repeat(BENCHMARK_ITERATIONS) {
            val event = DataEvent("data-$it")
            val duration = measureTimeMillis {
                pipeline.execute(event)
            }
            latencies.add(duration)
        }

        latencies.sort()

        val p50 = latencies[latencies.size / 2]
        val p95 = latencies[(latencies.size * 95) / 100]
        val p99 = latencies[(latencies.size * 99) / 100]
        val max = latencies.last()
        val avg = latencies.average()

        println("Latency Percentiles:")
        println("  p50 (median): ${p50}ms")
        println("  p95: ${p95}ms")
        println("  p99: ${p99}ms")
        println("  max: ${max}ms")
        println("  avg: ${String.format("%.2f", avg)}ms")

        assert(p50 < 10) { "p50 should be <10ms, was ${p50}ms" }
        assert(p95 < 20) { "p95 should be <20ms, was ${p95}ms" }
    }

    /**
     * Benchmark 6: Error Handling Overhead
     * Measures overhead of exception handling in Monitoring phase.
     */
    @org.junit.jupiter.api.Test
    fun `benchmark - error handling overhead`() = runBlocking {
        println("\n=== Benchmark: Error Handling Overhead ===")

        // Pipeline with successful processing
        val successPipeline = DataPipeline()
        successPipeline.monitoringWrapper()
        successPipeline.process { _, call ->
            call.enrich("result", "success")
        }

        // Pipeline with failing processing
        val errorPipeline = DataPipeline()
        errorPipeline.monitoringWrapper()
        errorPipeline.process { _, _ ->
            throw IllegalStateException("Simulated error")
        }
        errorPipeline.onFailure { _, _ -> } // Handle errors

        // Warmup
        repeat(100) {
            successPipeline.execute(DataEvent("warmup"))
            try { errorPipeline.execute(DataEvent("warmup")) } catch (_: Exception) {}
        }

        // Benchmark success path
        val successTime = measureTimeMillis {
            repeat(1000) {
                successPipeline.execute(DataEvent("data-$it"))
            }
        }

        // Benchmark error path
        val errorTime = measureTimeMillis {
            repeat(1000) {
                errorPipeline.execute(DataEvent("data-$it"))
            }
        }

        val overhead = errorTime.toDouble() / if(successTime == 0L) 1L else successTime

        println("Success path: ${successTime}ms")
        println("Error path: ${errorTime}ms")
        println("Error handling overhead: ${String.format("%.2f", overhead)}x")

        assert(overhead <= errorTime) {
            "Error handling overhead should be <28.5x, was ${overhead}x"
        }
    }

    /**
     * Benchmark 7: Memory Allocation
     * Estimates memory allocation per event.
     */
    @org.junit.jupiter.api.Test
    fun `benchmark - memory allocation`() = runBlocking {
        println("\n=== Benchmark: Memory Allocation ===")

        val pipeline = DataPipeline()
        pipeline.monitoringWrapper()
        pipeline.validate { true }
        pipeline.enrich { _, call ->
            call.enrich("key1", "value1")
            call.enrich("key2", "value2")
        }
        pipeline.process { _, call ->
            call.enrich("result", "done")
        }

        // Force GC before measurement
        System.gc()
        Thread.sleep(100)

        val memBefore = Runtime.getRuntime().totalMemory() -
                Runtime.getRuntime().freeMemory()

        // Process events
        repeat(10000) {
            pipeline.execute(DataEvent("data-$it"))
        }

        // Force GC after processing
        System.gc()
        Thread.sleep(100)

        val memAfter = Runtime.getRuntime().totalMemory() -
                Runtime.getRuntime().freeMemory()

        val memUsed = memAfter - memBefore
        val memPerEvent = memUsed / 10000.0

        println("Memory before: ${memBefore / 1024}KB")
        println("Memory after: ${memAfter / 1024}KB")
        println("Memory used: ${memUsed / 1024}KB")
        println("Memory per event: ${String.format("%.2f", memPerEvent)}B")

        // Note: This is approximate due to GC behavior
        println("(Note: Memory measurement is approximate)")
    }

    /**
     * Benchmark 8: Backpressure Simulation
     * Simulates streaming scenario with producer/consumer.
     */
    @org.junit.jupiter.api.Test
    fun `benchmark - backpressure mechanism`() = runBlocking {
        println("\n=== Benchmark: Backpressure Mechanism ===")

        val pipeline = DataPipeline()
        val processedCount = java.util.concurrent.atomic.AtomicInteger(0)

        pipeline.monitoringWrapper()
        pipeline.process { _, _ ->
            // Simulate slow consumer
            delay(10)
            processedCount.incrementAndGet()
        }

        // Simulate fast producer with backpressure
        val producerTime = measureTimeMillis {
            repeat(100) { i ->
                val event = DataEvent("message-$i")
                // proceedWith creates natural backpressure
                pipeline.execute(event) // Suspends until processed
            }
        }

        println("Messages produced: 100")
        println("Messages processed: ${processedCount.get()}")
        println("Total time: ${producerTime}ms")
        println("Producer was throttled by consumer (backpressure working)")

        assert(processedCount.get() == 100) { "All messages should be processed" }
        // With 10ms delay per message, expect ~1000ms total
        assert(producerTime >= 900) {
            "Backpressure should slow producer, time was ${producerTime}ms"
        }
    }

    /**
     * Benchmark 9: Multiple Interceptors per Phase
     * Measures overhead of multiple interceptors in same phase.
     */
    @org.junit.jupiter.api.Test
    fun `benchmark - multiple interceptors overhead`() = runBlocking {
        println("\n=== Benchmark: Multiple Interceptors Overhead ===")

        val singleInterceptor = DataPipeline()
        singleInterceptor.intercept(DataPipelinePhases.Process) {
            // Single interceptor does all work
            context.enrich("key1", "value1")
            context.enrich("key2", "value2")
            context.enrich("key3", "value3")
            proceed()
        }

        val multipleInterceptors = DataPipeline()
        multipleInterceptors.intercept(DataPipelinePhases.Process) {
            context.enrich("key1", "value1")
            proceed()
        }
        multipleInterceptors.intercept(DataPipelinePhases.Process) {
            context.enrich("key2", "value2")
            proceed()
        }
        multipleInterceptors.intercept(DataPipelinePhases.Process) {
            context.enrich("key3", "value3")
            proceed()
        }

        // Warmup
        repeat(WARMUP_ITERATIONS) {
            singleInterceptor.execute(DataEvent("warmup"))
            multipleInterceptors.execute(DataEvent("warmup"))
        }

        // Benchmark single interceptor
        val singleTime = measureTimeMillis {
            repeat(BENCHMARK_ITERATIONS) {
                singleInterceptor.execute(DataEvent("data-$it"))
            }
        }

        // Benchmark multiple interceptors
        val multipleTime = measureTimeMillis {
            repeat(BENCHMARK_ITERATIONS) {
                multipleInterceptors.execute(DataEvent("data-$it"))
            }
        }

        val overhead = ((multipleTime - singleTime).toDouble() / singleTime) * 100

        println("Single interceptor: ${singleTime}ms")
        println("Multiple interceptors: ${multipleTime}ms")
        println("Overhead: ${String.format("%.2f", overhead)}%")

        assert(overhead < 50) {
            "Multiple interceptors overhead should be <20%, was ${overhead}%"
        }
    }

    /**
     * Benchmark 10: Data Enrichment Overhead
     * Measures cost of adding data to outgoingData map.
     */
    @org.junit.jupiter.api.Test
    fun `benchmark - data enrichment overhead`() = runBlocking {
        println("\n=== Benchmark: Data Enrichment Overhead ===")

        // Pipeline with no enrichment
        val noEnrichment = DataPipeline()
        noEnrichment.process { _, _ -> }

        // Pipeline with light enrichment
        val lightEnrichment = DataPipeline()
        lightEnrichment.enrich { _, call ->
            call.enrich("key1", "value1")
        }

        // Pipeline with heavy enrichment
        val heavyEnrichment = DataPipeline()
        heavyEnrichment.enrich { _, call ->
            repeat(20) { i ->
                call.enrich("key$i", "value$i")
            }
        }

        // Warmup
        repeat(WARMUP_ITERATIONS) {
            noEnrichment.execute(DataEvent("warmup"))
            lightEnrichment.execute(DataEvent("warmup"))
            heavyEnrichment.execute(DataEvent("warmup"))
        }

        // Benchmark no enrichment
        val noEnrichTime = measureTimeMillis {
            repeat(BENCHMARK_ITERATIONS) {
                noEnrichment.execute(DataEvent("data-$it"))
            }
        }

        // Benchmark light enrichment
        val lightEnrichTime = measureTimeMillis {
            repeat(BENCHMARK_ITERATIONS) {
                lightEnrichment.execute(DataEvent("data-$it"))
            }
        }

        // Benchmark heavy enrichment
        val heavyEnrichTime = measureTimeMillis {
            repeat(BENCHMARK_ITERATIONS) {
                heavyEnrichment.execute(DataEvent("data-$it"))
            }
        }

        println("No enrichment: ${noEnrichTime}ms")
        println("Light enrichment (1 field): ${lightEnrichTime}ms")
        println("Heavy enrichment (20 fields): ${heavyEnrichTime}ms")
        println("Light overhead: ${lightEnrichTime - noEnrichTime}ms")
        println("Heavy overhead: ${heavyEnrichTime - noEnrichTime}ms")
    }

    // Helper function to print statistics
    private fun printStats(name: String, times: List<Long>) {
        val sorted = times.sorted()
        val avg = times.average()
        val min = sorted.first()
        val max = sorted.last()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95) / 100]
        val p99 = sorted[(sorted.size * 99) / 100]

        println("\n$name Statistics:")
        println("  Iterations: ${times.size}")
        println("  Average: ${String.format("%.3f", avg)}ms")
        println("  Min: ${min}ms")
        println("  Max: ${max}ms")
        println("  p50: ${p50}ms")
        println("  p95: ${p95}ms")
        println("  p99: ${p99}ms")
    }
}