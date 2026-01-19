package io.kurlew.pipeline

import io.kurlew.pipeline.extensions.process
import io.kurlew.pipeline.extensions.validate
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ProcessPhaseTest {

    @Test
    fun `process phase executes business logic`() = runBlocking {
        val pipeline = DataPipeline()
        var processed = false

        pipeline.process { event ->
            processed = true
            event.enrich("result", "success")
        }

        val event = DataEvent("data")
        pipeline.execute(event)

        assertTrue(processed)
        assertEquals("success", event.get<String>("result"))
    }

    @Test
    fun `process phase only receives validated data`() = runBlocking {
        val pipeline = DataPipeline()
        var processExecuted = false

        pipeline.validate { event ->
            event.incomingData is Int && event.incomingData > 0
        }

        pipeline.process { _ ->
            processExecuted = true
        }

        // Test with invalid data
        val invalidEvent = DataEvent(-1)
        pipeline.execute(invalidEvent)
        assertFalse(processExecuted, "Process should not execute for invalid data")

        // Test with valid data
        val validEvent = DataEvent(42)
        pipeline.execute(validEvent)
        assertTrue(processExecuted, "Process should execute for valid data")
    }
}