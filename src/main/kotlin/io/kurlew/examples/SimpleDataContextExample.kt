package io.kurlew.examples


import io.kurlew.pipeline.*
import io.kurlew.pipeline.extensions.*
import kotlinx.coroutines.runBlocking

/**
 * Example demonstrating the DataContext architecture.
 *
 * Shows how DataEvent (subject) and DataContext (context) are separate:
 * - DataEvent = what data is being processed
 * - DataContext = how to process it (services, cache, session, etc.)
 */
fun main() = runBlocking {
    println("=== Data Pipeline with DataContext Example ===\n")

    // Setup: Create shared services
    val database = MockDatabase()
    val userService = MockUserService()

    // Create pipeline
    val pipeline = DataPipeline()

    // Configure pipeline with context-aware interceptors
    setupPipeline(pipeline, database, userService)

    // Example 1: Process with default context
    println("--- Example 1: Default Context ---")
    processWithDefaultContext(pipeline)

    // Example 2: Process with custom context (HTTP source)
    println("\n--- Example 2: HTTP Source Context ---")
    processWithHttpContext(pipeline, database, userService)

    // Example 3: Process with session
    println("\n--- Example 3: Session Context ---")
    processWithSession(pipeline, database, userService)

    // Example 4: Process with caching
    println("\n--- Example 4: Caching ---")
    processWithCaching(pipeline, database, userService)
}

fun setupPipeline(pipeline: DataPipeline, database: MockDatabase, userService: MockUserService) {
    // Monitoring with tracing
    pipeline.monitoringWrapper(
        onError = { event, context, error ->
            println("[Monitoring] Error in ${context.correlationId}: ${error.message}")
            event.markFailed(error.message)
        },
        onSuccess = { event, context ->
            val duration = context.getAttribute<kotlin.time.Duration>("processingDuration")
            println("[Monitoring] Success in ${context.correlationId}, took $duration")
        }
    )

    // Validation with context access
    pipeline.validate { event, context ->
        val data = event.incomingData as? UserRequest

        if (data == null) {
            println("[Validation] Invalid data type")
            return@validate false
        }

        // Access cache for validation rules
        val validationRules = context.cache.get("rules") as ValidationRules?

        val isValid: Boolean = validationRules?.validate(data) ?: false
        println("[Validation] User ${data.userId}: ${if (isValid == true) "✓ valid" else "✗ invalid"}")

        isValid
    }

    // Enrichment with service access
    pipeline.enrich { event, context ->
        val request = event.incomingData as UserRequest

        // Access user service from context or use injected one
        val userData = userService.getUserData(request.userId)
        event.enrich("userName", userData.name)
        event.enrich("userEmail", userData.email)

        println("[Enrichment] Added user data: ${userData.name}")
    }

    // Processing with database access
    pipeline.process { event, context ->
        val request = event.incomingData as UserRequest

        // Use correlation ID for tracking
        println("[Process] Processing ${context.correlationId}")

        // Save to database
        database.save(request)
        event.enrich("saved", true)

        // Track in session if available
        context.session?.set("lastProcessed", request.action)

        println("[Process] Saved action: ${request.action}")
    }

    // Fallback with context access
    pipeline.onFailure { event, context ->
        println("[Fallback] Failed event ${context.correlationId}")
        println("  Source: ${context.source}")
        println("  Error: ${event.getError()}")

        // Log to DLQ with full context
        val dlqEntry = DLQEntry(
            correlationId = context.correlationId,
            event = event,
            source = context.source,
            timestamp = System.currentTimeMillis()
        )
        println("  Added to DLQ: $dlqEntry")
    }

    pipeline.onSuccess { event, context ->
        println("[Fallback] Success ${context.correlationId}")
    }
}

suspend fun processWithDefaultContext(pipeline: DataPipeline) {
    val request = UserRequest(userId = 123, action = "login")
    val event = DataEvent(request)

    // Execute with default context
    pipeline.execute(event)
}

suspend fun processWithHttpContext(
    pipeline: DataPipeline,
    database: MockDatabase,
    userService: MockUserService
) {
    val request = UserRequest(userId = 456, action = "purchase")
    val event = DataEvent(request)

    // Build context with HTTP source
    val context = pipeline.contextBuilder()
        .correlationId("http-req-12345")
        .source(SourceContext.Http(
            method = "POST",
            uri = "/api/users/456/actions",
            headers = mapOf("User-Agent" to "Mozilla/5.0"),
            remoteAddress = "192.168.1.100"
        ))
        .service("database", database)
        .service("userService", userService)
        .build()

    pipeline.execute(event, context)
}

suspend fun processWithSession(
    pipeline: DataPipeline,
    database: MockDatabase,
    userService: MockUserService
) {
    val request = UserRequest(userId = 789, action = "logout")
    val event = DataEvent(request)

    // Create session
    val session = SessionContext(
        sessionId = "session-abc-123"
    ).apply {
        set("userId", 789)
        set("loginTime", System.currentTimeMillis())
    }

    // Build context with session
    val context = pipeline.contextBuilder()
        .correlationId("session-req-67890")
        .session(session)
        .service("database", database)
        .service("userService", userService)
        .build()

    pipeline.execute(event, context)

    // Session is updated
    println("Session last activity: ${session.lastActivityAt}")
    println("Session last processed: ${session.get<String>("lastProcessed")}")
}

suspend fun processWithCaching(
    pipeline: DataPipeline,
    database: MockDatabase,
    userService: MockUserService
) {
    // Add caching to pipeline
    val cachingPipeline = DataPipeline()

    // Copy configuration from main pipeline
    setupPipeline(cachingPipeline, database, userService)

    // Add caching layer
    cachingPipeline.withCache(
        keyProvider = { event ->
            val req = event.incomingData as UserRequest
            "user:${req.userId}"
        },
        ttlMs = 60_000 // 1 minute
    )

    val request = UserRequest(userId = 999, action = "view")

    // First call - cache miss
    println("First call (cache miss):")
    val event1 = DataEvent(request)
    val context1 = pipeline.contextBuilder()
        .service("database", database)
        .service("userService", userService)
        .build()
    cachingPipeline.execute(event1, context1)
    println("  Cache hit: ${context1.getAttribute<Boolean>("cacheHit")}")

    // Second call - cache hit
    println("Second call (cache hit):")
    val event2 = DataEvent(request)
    val context2 = pipeline.contextBuilder()
        .service("database", database)
        .service("userService", userService)
        .build()
    // Reuse cache from previous context
    context2.cache.put("user:999", event1.outgoingData, 60_000)
    cachingPipeline.execute(event2, context2)
    println("  Cache hit: ${context2.getAttribute<Boolean>("cacheHit")}")
}

// Domain models
data class UserRequest(val userId: Int, val action: String)
data class UserData(val name: String, val email: String)
data class ValidationRules(val minUserId: Int) {
    fun validate(request: UserRequest): Boolean {
        return request.userId >= minUserId
    }

    companion object {
        fun default() = ValidationRules(minUserId = 1)
    }
}

data class DLQEntry(
    val correlationId: String,
    val event: DataEvent,
    val source: SourceContext?,
    val timestamp: Long
)

// Mock services
class MockDatabase {
    fun save(request: UserRequest) {
        // Simulate database save
    }
}

class MockUserService {
    fun getUserData(userId: Int): UserData {
        return UserData(
            name = "User $userId",
            email = "user$userId@example.com"
        )
    }
}