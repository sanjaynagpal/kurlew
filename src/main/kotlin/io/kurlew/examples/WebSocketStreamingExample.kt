package io.kurlew.examples

import io.kurlew.pipeline.DataEvent
import io.kurlew.pipeline.DataPipeline
import io.kurlew.pipeline.extensions.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

/**
 * Example demonstrating the Data Pipeline's natural backpressure mechanism
 * with a one-to-many streaming scenario (WebSocket-like).
 *
 * This example shows how the pipeline's suspending nature automatically
 * throttles the producer to match consumer capacity, preventing overload.
 */
fun main() = runBlocking {
    println("=== WebSocket Streaming Example with Backpressure ===\n")

    val pipeline = createStreamingPipeline()
    val messageStream = simulateWebSocketStream()

    // Process messages from the stream
    processStreamWithBackpressure(pipeline, messageStream)
}

/**
 * Creates a pipeline configured for streaming data processing.
 */
fun createStreamingPipeline(): DataPipeline {
    val pipeline = DataPipeline()

    // Monitoring with timing
    pipeline.monitoringWrapper(
        onError = { event, _, error ->
            println("[Error] Failed to process message: ${error.message}")
            event.markFailed(error.message)
        }
    ) {
        val startTime = System.currentTimeMillis()
        subject.enrich("startTime", startTime)
    }

    // Validation - filter out invalid messages early
    pipeline.validate { event, _ ->
        val message = event.incomingData as? ChatMessage
        message != null && message.content.isNotBlank()
    }

    // Enrichment
    pipeline.enrich { event, _ ->
        val message = event.incomingData as ChatMessage
        event.enrich("processedAt", System.currentTimeMillis())
        event.enrich("contentLength", message.content.length)
    }

    // Processing - simulate slow I/O operation
    pipeline.process { event, _ ->
        val message = event.incomingData as ChatMessage

        // Simulate database write (slow operation)
        delay(500) // 500ms per message

        println("[Process] Saved message from ${message.sender}: ${message.content.take(30)}...")
        event.enrich("saved", true)
    }

    // Dead-letter queue for failed messages
    pipeline.onFailure { _, _ ->
        println("[DLQ] Message failed validation or processing")
    }

    return pipeline
}

/**
 * Simulates a WebSocket message stream.
 * Generates messages at a fast rate.
 */
fun simulateWebSocketStream(): Flow<ChatMessage> = flow {
    val messages = listOf(
        ChatMessage("alice", "Hello everyone!"),
        ChatMessage("bob", "Hi Alice!"),
        ChatMessage("charlie", "How are you all doing?"),
        ChatMessage("alice", "Great! Working on this cool pipeline project"),
        ChatMessage("bob", ""),  // Invalid: empty message
        ChatMessage("david", "That sounds interesting!"),
        ChatMessage("alice", "The backpressure mechanism is really elegant"),
        ChatMessage("charlie", "Tell me more about it")
    )

    for (message in messages) {
        emit(message)
        delay(100) // Messages arrive every 100ms
    }
}

/**
 * Processes the stream with natural backpressure.
 *
 * Key observation: The Acquire phase calls proceedWith(), which suspends
 * until the entire pipeline finishes processing the current event. This
 * automatically throttles the producer!
 */
suspend fun processStreamWithBackpressure(
    pipeline: DataPipeline,
    stream: Flow<ChatMessage>
) {
    println("Starting stream processing...")
    println("Note: Messages arrive every 100ms, but processing takes 500ms")
    println("The pipeline will naturally slow down the producer!\n")

    var messageCount = 0
    val startTime = System.currentTimeMillis()

    stream.collect { message ->
        messageCount++
        val messageStartTime = System.currentTimeMillis()
        println("\n[Acquire] Received message #$messageCount at ${messageStartTime - startTime}ms")

        // Create event and process it
        // proceedWith() suspends until processing completes!
        val event = DataEvent(message)
        pipeline.execute(event)

        val messageEndTime = System.currentTimeMillis()
        val processingTime = messageEndTime - messageStartTime

        println("[Acquire] Message #$messageCount completed in ${processingTime}ms")
        println("         Backpressure in action: Producer waited for consumer!")
    }

    val totalTime = System.currentTimeMillis() - startTime
    println("\n=== Summary ===")
    println("Total messages: $messageCount")
    println("Total time: ${totalTime}ms")
    println("Average time per message: ${totalTime / messageCount}ms")
    println("\nWithout backpressure, all messages would arrive in ${messageCount * 100}ms")
    println("With backpressure, processing matches consumer capacity!")
}

data class ChatMessage(
    val sender: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
