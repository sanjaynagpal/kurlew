package io.kurlew.examples

import io.kurlew.pipeline.*
import io.kurlew.pipeline.extensions.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * Example demonstrating resilient persistence with retry logic.
 *
 * Shows how the Data Pipeline handles:
 * - Transient I/O failures with retry
 * - Permanent failures with dead-letter queue
 * - Detailed error tracking in outgoingData
 */
fun main() = runBlocking {
    println("=== Resilient Persistence Example ===\n")

    val pipeline = createResilientPipeline()

    // Test with various scenarios
    println("--- Scenario 1: Successful save ---")
    val successEvent = DataEvent(OrderData(orderId = "ORDER-001", amount = 100.0))
    pipeline.execute(successEvent)

    println("\n--- Scenario 2: Transient failure (retry succeeds) ---")
    DatabaseSimulator.failureCount = 2 // Will fail 2 times, then succeed
    val retryEvent = DataEvent(OrderData(orderId = "ORDER-002", amount = 200.0))
    pipeline.execute(retryEvent)

    println("\n--- Scenario 3: Permanent failure (all retries exhausted) ---")
    DatabaseSimulator.failureCount = 5 // Will fail all attempts
    val failEvent = DataEvent(OrderData(orderId = "ORDER-003", amount = 300.0))
    val x = pipeline.execute(failEvent)
}

fun createResilientPipeline(): DataPipeline {
    val pipeline = DataPipeline()
    val deadLetterQueue = mutableListOf<DataEvent>()

    // Monitoring with detailed error tracking
    pipeline.monitoringWrapper(
        onError = { event, error ->
            println("[Monitoring] Caught exception: ${error.message}")
            event.markFailed(error.message)
            event.enrich("errorType", error::class.simpleName ?: "Unknown")
            event.enrich("errorTime", System.currentTimeMillis())
        }
    )

    // Validation
    pipeline.validate { event ->
        val order = event.incomingData as? OrderData
        order != null && order.amount > 0
    }

    // Process with retry logic
    pipeline.intercept(DataPipelinePhases.Process) {
        val order = subject.incomingData as OrderData
        var attempt = 0
        var lastError: Exception? = null
        val maxAttempts = 3

        while (attempt < maxAttempts) {
            attempt++
            try {
                println("[Process] Attempt $attempt/$maxAttempts: Saving order ${order.orderId}")

                // Simulate database save (may fail)
                DatabaseSimulator.save(order)

                println("[Process] ✓ Successfully saved order ${order.orderId}")
                subject.enrich("saved", true)
                subject.enrich("attempts", attempt)
                proceed()
                return@intercept

            } catch (e: Exception) {
                lastError = e
                println("[Process] ✗ Attempt $attempt failed: ${e.message}")

                if (attempt < maxAttempts) {
                    delay(1.seconds)
                    println("[Process] Retrying...")
                }
            }
        }

        // All retries exhausted
        println("[Process] All retry attempts exhausted")
        throw lastError ?: IllegalStateException("Unknown error during persistence")
    }

    // Fallback - Dead Letter Queue
    pipeline.onFailure { event ->
        println("[Fallback] Adding failed event to DLQ")
        deadLetterQueue.add(event)

        val order = event.incomingData as OrderData
        println("  - Order ID: ${order.orderId}")
        println("  - Error: ${event.getError()}")
        println("  - Error Type: ${event.get<String>("errorType")}")
        println("  - DLQ Size: ${deadLetterQueue.size}")
    }

    pipeline.onSuccess { event ->
        val order = event.incomingData as OrderData
        val attempts = event.get<Int>("attempts") ?: 1
        println("[Fallback] Order ${order.orderId} successfully processed after $attempts attempt(s)")
    }

    return pipeline
}

data class OrderData(
    val orderId: String,
    val amount: Double
)

/**
 * Simulates a database with configurable failure behavior.
 */
object DatabaseSimulator {
    var failureCount = 0
    private var currentAttempt = 0

    fun save(order: OrderData) {
        currentAttempt++

        if (currentAttempt <= failureCount) {
            throw java.io.IOException("Database connection failed")
        }

        // Success - reset for next operation
        currentAttempt = 0
        // Simulate successful save
    }
}