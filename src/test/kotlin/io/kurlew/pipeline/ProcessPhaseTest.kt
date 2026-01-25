package io.kurlew.pipeline

import io.kurlew.pipeline.extensions.process
import io.kurlew.pipeline.extensions.validate
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ProcessPhaseTest {

    @Test
    fun `process phase executes business logic`() = runBlocking {
        val pipeline = DataPipeline<String>()
        var processed = false

        pipeline.process { _, call ->
            processed = true
            call.enrich("result", "success")
        }

        val event = DataEvent("data")
        val call = pipeline.execute(event)

        assertTrue(processed)
        assertEquals("success", call.get<String>("result"))
    }

    @Test
    fun `process phase only receives validated data`() = runBlocking {
        val pipeline = DataPipeline<Int>()
        var processExecuted = false

        pipeline.validate { event ->
            event.incoming > 0
        }

        pipeline.process { _, _ ->
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