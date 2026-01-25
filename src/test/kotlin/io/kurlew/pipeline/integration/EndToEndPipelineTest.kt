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
        pipeline.validate { event ->
            event.incomingData is UserRequest
        }

        pipeline.intercept(DataPipelinePhases.Features) {
            val request = subject.incomingData as UserRequest
            if (request.userId <= 0) {
                context.markFailed("Invalid user ID")
                finish()
                return@intercept
            }
            context.enrich("validatedUserId", request.userId)
            proceed()
        }

        // Processing
        pipeline.process { event, call ->
            val request = event.incomingData as UserRequest
            processedRequests.add(request)
            call.enrich("processedAt", System.currentTimeMillis())
        }

        // Success/Failure handlers
        pipeline.onSuccess { _, call ->
            call.enrich("status", "completed")
        }

        pipeline.onFailure { _, call ->
            call.enrich("status", "failed")
        }

        // Execute with valid request
        val validRequest = UserRequest(userId = 123, action = "login")
        val call = pipeline.executeRaw(validRequest)

        assertEquals(1, processedRequests.size)
        assertEquals("completed", call.get<String>("status"))
        assertNotNull(call.get<Long>("processedAt"))
        assertFalse(call.isFailed())
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
                context.markFailed("Invalid request or user ID")
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
        val call = pipeline.executeRaw(invalidRequest)

        assertEquals(0, processedRequests.size, "Invalid request should not be processed")
        assertEquals(1, failedRequests.size, "Failed request should be in failedRequests")
        assertTrue(call.isFailed())
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
        val call = pipeline.executeRaw(request)

        assertTrue(call.isFailed())
        assertEquals("Database connection failed", call.getError())
        assertEquals(1, failedRequests.size)
    }
}