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
        val pipeline = DataPipeline<String>()
        var errorCaught = false

        pipeline.monitoringWrapper(
            onError = { _, call, error ->
                errorCaught = true
                call.markFailed(error.message)
            }
        )

        pipeline.intercept(DataPipelinePhases.Process) {
            throw IllegalStateException("Simulated error")
        }

        val event = DataEvent("data")
        val call = pipeline.execute(event)

        assertTrue(errorCaught, "Monitoring should catch exceptions")
        assertTrue(call.isFailed(), "Event should be marked as failed")
        assertEquals("Simulated error", call.getError())
    }

    @Test
    fun `monitoring allows event to proceed to Fallback after error`() = runBlocking {
        val pipeline = DataPipeline<String>()
        var fallbackExecuted = false

        pipeline.monitoringWrapper(
            onError = { _, call, error ->
                call.markFailed(error.message)
            }
        )

        pipeline.intercept(DataPipelinePhases.Process) {
            throw IllegalStateException("Error in Process")
        }

        pipeline.intercept(DataPipelinePhases.Fallback) {
            if (context.isFailed()) {
                fallbackExecuted = true
            }
        }

        val event = DataEvent("data")
        pipeline.execute(event)

        assertTrue(fallbackExecuted, "Fallback should execute for failed events")
    }

    @Test
    fun `monitoring records processing duration`(): Unit = runBlocking {
        val pipeline = DataPipeline<String>()

        pipeline.monitoringWrapper()

        pipeline.intercept(DataPipelinePhases.Process) {
            delay(100) // Simulate work
            proceed()
        }

        val event = DataEvent("data")
        val call = pipeline.execute(event)

        assertNotNull(call.get<Duration>("processingDuration"))
    }
}