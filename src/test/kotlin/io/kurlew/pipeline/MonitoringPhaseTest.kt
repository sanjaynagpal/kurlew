package io.kurlew.pipeline

import io.kurlew.pipeline.extensions.monitoringWrapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class MonitoringPhaseTest {

    @Test
    fun `monitoring catches exceptions from Process phase`() = runBlocking {
        val pipeline = DataPipeline()
        var errorCaught = false

        pipeline.monitoringWrapper(
            onError = { event, _, error ->
                errorCaught = true
                event.markFailed(error.message)
            }
        )

        pipeline.intercept(DataPipelinePhases.Process) {
            throw IllegalStateException("Simulated error")
        }

        val event = DataEvent("data")
        pipeline.execute(event)

        assertTrue(errorCaught, "Monitoring should catch exceptions")
        assertTrue(event.isFailed(), "Event should be marked as failed")
        assertEquals("Simulated error", event.getError())
    }

    @Test
    fun `monitoring allows event to proceed to Fallback after error`() = runBlocking {
        val pipeline = DataPipeline()
        var fallbackExecuted = false

        pipeline.monitoringWrapper(
            onError = { event, _, error ->
                event.markFailed(error.message)
            }
        )

        pipeline.intercept(DataPipelinePhases.Process) {
            throw IllegalStateException("Error in Process")
        }

        pipeline.intercept(DataPipelinePhases.Fallback) {
            if (subject.isFailed()) {
                fallbackExecuted = true
            }
        }

        val event = DataEvent("data")
        pipeline.execute(event)

        assertTrue(fallbackExecuted, "Fallback should execute for failed events")
    }

    @Test
    fun `monitoring records processing duration`(): Unit = runBlocking {
        val pipeline = DataPipeline()

        pipeline.monitoringWrapper()

        pipeline.intercept(DataPipelinePhases.Process) {
            delay(100) // Simulate work
            proceed()
        }

        val event = DataEvent("data")
        pipeline.execute(event)

        assertNotNull(event.get<Duration>("processingDuration"))
    }
}