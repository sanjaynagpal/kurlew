package io.kurlew.pipeline.integration


import io.kurlew.pipeline.*
import io.kurlew.pipeline.extensions.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class EndToEndPipelineTest {

    data class UserRequest(val userId: Int, val action: String)

    @Test
    fun `complete pipeline processes valid request successfully`() = runBlocking {
        val pipeline = DataPipeline()
        val processedRequests = mutableListOf<UserRequest>()

        // Monitoring
        pipeline.monitoringWrapper()

        // Validation
        pipeline.validate { event, _ ->
            event.incomingData is UserRequest
        }

        pipeline.intercept(DataPipelinePhases.Features) {
            val request = subject.incomingData as UserRequest
            if (request.userId <= 0) {
                subject.markFailed("Invalid user ID")
                finish()
                return@intercept
            }
            subject.enrich("validatedUserId", request.userId)
            proceed()
        }

        // Processing
        pipeline.process { event, _ ->
            val request = event.incomingData as UserRequest
            processedRequests.add(request)
            event.enrich("processedAt", System.currentTimeMillis())
        }

        // Success/Failure handlers
        pipeline.onSuccess { event, _ ->
            event.enrich("status", "completed")
        }

        pipeline.onFailure { event, _ ->
            event.enrich("status", "failed")
        }

        // Execute with valid request
        val validRequest = UserRequest(userId = 123, action = "login")
        val event = pipeline.executeRaw(validRequest)

        assertEquals(1, processedRequests.size)
        assertEquals("completed", event.get<String>("status"))
        assertNotNull(event.get<Long>("processedAt"))
        assertFalse(event.isFailed())
    }

    @Test
    fun `complete pipeline handles validation failure`() = runBlocking {
        val pipeline = DataPipeline()
        val processedRequests = mutableListOf<UserRequest>()
        val failedRequests = mutableListOf<DataEvent>()

        pipeline.monitoringWrapper()

        pipeline.intercept(DataPipelinePhases.Features) {
            val request = subject.incomingData as? UserRequest
            if (request == null || request.userId <= 0) {
                subject.markFailed("Invalid request or user ID")
                // Don't call finish() - let it proceed to Fallback
            }
            proceed()
        }

        pipeline.process { event, _ ->
            // This check is now inside the process extension
            processedRequests.add(event.incomingData as UserRequest)
        }

        pipeline.onFailure { event, _ ->
            failedRequests.add(event)
        }

        // Execute with invalid request
        val invalidRequest = UserRequest(userId = -1, action = "login")
        val event = pipeline.executeRaw(invalidRequest)

        assertEquals(0, processedRequests.size, "Invalid request should not be processed")
        assertEquals(1, failedRequests.size, "Failed request should be in failedRequests")
        assertTrue(event.isFailed())
    }

    @Test
    fun `complete pipeline handles processing exceptions`() = runBlocking {
        val pipeline = DataPipeline()
        val failedRequests = mutableListOf<DataEvent>()

        pipeline.monitoringWrapper()

        pipeline.process { _, _ ->
            throw IllegalStateException("Database connection failed")
        }

        pipeline.onFailure { event, _ ->
            failedRequests.add(event)
        }

        val request = UserRequest(userId = 123, action = "login")
        val event = pipeline.executeRaw(request)

        assertTrue(event.isFailed())
        assertEquals("Database connection failed", event.getError())
        assertEquals(1, failedRequests.size)
    }
}