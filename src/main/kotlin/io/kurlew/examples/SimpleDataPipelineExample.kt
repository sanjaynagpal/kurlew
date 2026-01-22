package io.kurlew.examples

import io.kurlew.pipeline.DataEvent
import io.kurlew.pipeline.DataPipeline
import io.kurlew.pipeline.DataPipelinePhases
import io.kurlew.pipeline.extensions.enrich
import io.kurlew.pipeline.extensions.monitoringWrapper
import io.kurlew.pipeline.extensions.onFailure
import io.kurlew.pipeline.extensions.onSuccess
import io.kurlew.pipeline.extensions.process
import io.kurlew.pipeline.extensions.validate
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("=== Simple Data Pipeline==")

    // Create the pipeline
    val pipeline = DataPipeline()

    // === Phase 1: Acquire ===
    pipeline.intercept(DataPipelinePhases.Acquire) {
        println("[Acquire] Received registration request")
        // In real scenario, this will fetch from an API or message queue
        proceed()
    }

    // === Phase 2: Monitoring ===
    // Add monitoring with error handling
    pipeline.monitoringWrapper(
        onError = { event, _, error ->
            println("[Monitoring] Received registration request")
            event.markFailed(error.message)
        },
        onSuccess = { event, _ ->
            println("[Monitoring] Received registration request: $event")
        }
    ) {
        println("[Monitoring] Starting pipeline execution")
    }

    // === Phase 3: Features ===
    pipeline.validate("Invalid user data") { event, _ ->
        val userData = event.incomingData as? UserRegistration
        userData != null && userData.email.contains("@") && userData.age > 18
    }

    // Enrich with additional data
    pipeline.enrich { event, _ ->
        println("[Features] Validating and enriching data")
        event.enrich("timestamp", System.currentTimeMillis())
        event.enrich("source", "registration-api")
    }

    // === Phase 4: Process ===
    // Execute the main business logic
    pipeline.process { event, _ ->
        println("[Process] Saving user to database")
        val user = event.incomingData as UserRegistration

        // Simulate database save
        println("  - Saving user: ${user.email}")
        event.enrich("userId", generateUserId(user.email))
        event.enrich("saved", true)
    }

    // === Phase 5: Fallback ===
    // Handle failures
    pipeline.onFailure { event, _ ->
        println("[Fallback] Event failed: ${event.getError()}")
        println("  - Saving to dead-letter queue")
        // In real scenarios, save to DLQ
        logFailedEvent(event)
    }
    // Handle successes
    pipeline.onSuccess { event, _ ->
        println("[Fallback] Event processed successfully")
        val userId = event.get<String>("userId")
        println("  - User ID: $userId")
    }

    // === Execute Pipeline ===
    println("\n--- Processing valid User ---")
    val validUser = UserRegistration(
        email = "alice@example.com",
        name = "Alice",
        age = 25,
    )
    val x = pipeline.executeRaw(validUser)

    println("\n--- Processing Invalid User (underage) ---")
    val invalidUser = UserRegistration(
        email = "bob@example.com",
        age = 16,
        name = "Bob"
    )
    val y = pipeline.executeRaw(invalidUser)
}

data class UserRegistration(
    val email: String,
    val age: Int,
    val name: String
)

fun generateUserId(email: String): String {
    return "user_${email.hashCode().toString(16)}"
}

fun logFailedEvent(event: DataEvent) {
    println("  - DLQ Entry: ${event.incomingData}")
    println("  - Error: ${event.getError()}")
}