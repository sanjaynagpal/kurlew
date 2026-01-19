package io.kurlew.pipeline

import io.kurlew.pipeline.extensions.onFailure
import io.kurlew.pipeline.extensions.onSuccess
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class FallbackPhaseTest {

    @Test
    fun `fallback handles failed events`() = runBlocking {
        val pipeline = DataPipeline()
        var fallbackExecuted = false
        val deadLetterQueue = mutableListOf<DataEvent>()

        pipeline.intercept(DataPipelinePhases.Process) {
            throw IllegalStateException("Simulated failure")
        }

        pipeline.intercept(DataPipelinePhases.Monitoring) {
            try {
                proceed()
            } catch (e: Exception) {
                subject.markFailed(e.message)
                proceed()
            }
        }

        pipeline.onFailure { event ->
            fallbackExecuted = true
            deadLetterQueue.add(event)
        }

        val event = DataEvent("data")
        pipeline.execute(event)

        assertTrue(fallbackExecuted, "Fallback should execute for failed events")
        assertEquals(1, deadLetterQueue.size)
        assertTrue(deadLetterQueue[0].isFailed())
    }

    @Test
    fun `success handler only executes for successful events`() = runBlocking {
        val pipeline = DataPipeline()
        var successHandlerExecuted = false
        var failureHandlerExecuted = false

        pipeline.onSuccess {
            successHandlerExecuted = true
        }

        pipeline.onFailure {
            failureHandlerExecuted = true
        }

        // Test with successful event
        val successEvent = DataEvent("data")
        pipeline.execute(successEvent)
        assertTrue(successHandlerExecuted)
        assertFalse(failureHandlerExecuted)

        // Reset and test with failed event
        successHandlerExecuted = false
        failureHandlerExecuted = false

        val pipeline2 = DataPipeline()
        pipeline2.intercept(DataPipelinePhases.Process) {
            subject.markFailed("Test failure")
            proceed()
        }
        pipeline2.onSuccess { successHandlerExecuted = true }
        pipeline2.onFailure { failureHandlerExecuted = true }

        val failedEvent = DataEvent("data")
        pipeline2.execute(failedEvent)

        assertFalse(successHandlerExecuted)
        assertTrue(failureHandlerExecuted)
    }
}