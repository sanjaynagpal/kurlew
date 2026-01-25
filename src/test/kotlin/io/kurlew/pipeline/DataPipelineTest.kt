package io.kurlew.pipeline

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DataPipelineTest {

    @Test
    fun `pipeline executes phases in correct order`() = runBlocking {
        val executionOrder = mutableListOf<String>()
        val pipeline = DataPipeline()

        pipeline.intercept(DataPipelinePhases.Setup) {
            executionOrder.add("Setup")
            proceed()
        }

        pipeline.intercept(DataPipelinePhases.Monitoring) {
            executionOrder.add("Monitoring")
            proceed()
        }

        pipeline.intercept(DataPipelinePhases.Features) {
            executionOrder.add("Features")
            proceed()
        }

        pipeline.intercept(DataPipelinePhases.Process) {
            executionOrder.add("Process")
            proceed()
        }

        pipeline.intercept(DataPipelinePhases.Fallback) {
            executionOrder.add("Fallback")
        }

        val event = DataEvent("test-data")
        pipeline.execute(event)

        assertEquals(
            listOf("Setup", "Monitoring", "Features", "Process", "Fallback"),
            executionOrder,
            "Phases should execute in the correct order"
        )
    }

    @Test
    fun `incomingData remains immutable throughout pipeline`() = runBlocking {
        val pipeline = DataPipeline()
        val originalData = "original-data"

        pipeline.intercept(DataPipelinePhases.Process) {
            // Attempt to verify immutability - incomingData reference cannot change
            assertEquals(originalData, subject.incomingData)
            proceed()
        }

        val event = DataEvent(originalData)
        pipeline.execute(event)

        assertEquals(originalData, event.incomingData, "incomingData must remain unchanged")
    }

    @Test
    fun `call attributes accumulate across phases`() = runBlocking {
        val pipeline = DataPipeline()

        pipeline.intercept(DataPipelinePhases.Features) {
            context.enrich("feature1", "value1")
            proceed()
        }

        pipeline.intercept(DataPipelinePhases.Features) {
            context.enrich("feature2", "value2")
            assertEquals("value1", context.get<String>("feature1"),
                "Previous enrichment should be accessible")
            proceed()
        }

        pipeline.intercept(DataPipelinePhases.Process) {
            assertEquals("value1", context.get<String>("feature1"))
            assertEquals("value2", context.get<String>("feature2"))
            context.enrich("processed", true)
            proceed()
        }

        val event = DataEvent("data")
        val call = pipeline.execute(event)

        assertEquals("value1", call.get<String>("feature1"))
        assertEquals("value2", call.get<String>("feature2"))
        assertEquals(true, call.get<Boolean>("processed"))
    }

    @Test
    fun `finish() short-circuits pipeline execution`() = runBlocking {
        val executionOrder = mutableListOf<String>()
        val pipeline = DataPipeline()

        pipeline.intercept(DataPipelinePhases.Features) {
            executionOrder.add("Features")
            finish() // Short-circuit here
        }

        pipeline.intercept(DataPipelinePhases.Process) {
            executionOrder.add("Process")
            proceed()
        }

        pipeline.intercept(DataPipelinePhases.Fallback) {
            executionOrder.add("Fallback")
        }

        val event = DataEvent("data")
        pipeline.execute(event)

        assertTrue(
            executionOrder.contains("Features"),
            "Features phase should execute"
        )
        assertFalse(
            executionOrder.contains("Process"),
            "Process phase should not execute after finish()"
        )
        assertFalse(
            executionOrder.contains("Fallback"),
            "Fallback phase should not execute after finish()"
        )
    }
}