package io.kurlew.examples

import io.kurlew.pipeline.*
import io.kurlew.pipeline.extensions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Example showing WebSocket streaming with DataContext.
 *
 * Demonstrates:
 * - Source context tracking (WebSocket connection)
 * - Session management across messages
 * - Natural backpressure with context
 */
fun main() = runBlocking {
    println("=== WebSocket Streaming with DataContext ===\n")

    val pipeline = createWebSocketPipeline()
    val messageStream = simulateWebSocketChatStream()

    // Simulate WebSocket connection
    val connectionId = "ws-conn-${System.currentTimeMillis()}"
    val session = SessionContext(sessionId = "ws-session-$connectionId")

    processWebSocketMessages(pipeline, messageStream, connectionId, session)
}

fun createWebSocketPipeline(): DataPipeline {
    val pipeline = DataPipeline()

    pipeline.monitoringWrapper()

    pipeline.validate { event, context ->
        val message = event.incomingData as? ChatMessage
        if (message == null || message.content.isBlank()) {
            println("[WebSocket] Invalid message from ${context.source}")
            return@validate false
        }
        true
    }

    pipeline.enrich { event, context ->
        val message = event.incomingData as ChatMessage

        // Track in session
        context.session?.let { session ->
            val messageCount = session.get<Int>("messageCount") ?: 0
            session.set("messageCount", messageCount + 1)
            event.enrich("messageNumber", messageCount + 1)
        }

        event.enrich("connectionId", (context.source as? SourceContext.WebSocket)?.connectionId as String)
    }

    pipeline.process { event, context ->
        val message = event.incomingData as ChatMessage

        // Simulate slow processing
        delay(500)

        println("[Process] Message #${event.get<Int>("messageNumber")} from ${message.sender}: ${message.content.take(30)}...")
    }

    pipeline.onFailure { event, context ->
        println("[DLQ] Failed message from connection ${(context.source as? SourceContext.WebSocket)?.connectionId}")
    }

    return pipeline
}

suspend fun processWebSocketMessages(
    pipeline: DataPipeline,
    stream: Flow<ChatMessage>,
    connectionId: String,
    session: SessionContext
) {
    println("Processing WebSocket messages from connection: $connectionId\n")

    var messageCount = 0
    val startTime = System.currentTimeMillis()

    stream.collect { message ->
        messageCount++
        val messageStartTime = System.currentTimeMillis()

        println("[WebSocket] Received message #$messageCount at ${messageStartTime - startTime}ms")

        // Create context for this message
        val context = DataContext(
            correlationId = "$connectionId-msg-$messageCount",
            source = SourceContext.WebSocket(
                connectionId = connectionId,
                uri = "/chat",
                headers = mapOf("Sec-WebSocket-Protocol" to "chat")
            ),
            session = session,
            coroutineContext = Dispatchers.Default
        )

        // Process with backpressure
        val event = DataEvent(message)
        pipeline.execute(event, context)

        val messageEndTime = System.currentTimeMillis()
        println("[WebSocket] Message #$messageCount completed in ${messageEndTime - messageStartTime}ms")
        println("            Total messages in session: ${session.get<Int>("messageCount")}\n")
    }

    val totalTime = System.currentTimeMillis() - startTime
    println("\n=== WebSocket Session Summary ===")
    println("Total messages: $messageCount")
    println("Total time: ${totalTime}ms")
    println("Session ID: ${session.sessionId}")
    println("Session message count: ${session.get<Int>("messageCount")}")
}

fun simulateWebSocketChatStream(): Flow<ChatMessage> = flow {
    val messages = listOf(
        ChatMessage("alice", "Hello everyone!"),
        ChatMessage("bob", "Hi Alice!"),
        ChatMessage("charlie", "How are you?"),
        ChatMessage("alice", "Great, thanks!"),
        ChatMessage("bob", ""),  // Invalid
        ChatMessage("david", "What's new?")
    )

    for (message in messages) {
        emit(message)
        delay(100)
    }
}