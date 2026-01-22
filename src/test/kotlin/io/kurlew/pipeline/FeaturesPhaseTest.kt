package io.kurlew.pipeline
import io.kurlew.pipeline.extensions.validate
import io.kurlew.pipeline.extensions.enrich
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class FeaturesPhaseTest {

    @Test
    fun `validation passes for valid data`() = runBlocking {
        val pipeline = DataPipeline()
        var processExecuted = false

        pipeline.validate { event, _ ->
            event.incomingData is String && event.incomingData.isNotEmpty()
        }

        pipeline.intercept(DataPipelinePhases.Process) {
            processExecuted = true
            proceed()
        }

        val event = DataEvent("valid-data")
        pipeline.execute(event)

        assertTrue(processExecuted, "Process should execute for valid data")
        assertFalse(event.isFailed())
    }

    @Test
    fun `validation fails and allows processing to Fallback`() = runBlocking {
        val pipeline = DataPipeline()
        var processExecuted = false
        var fallbackExecuted = false

        pipeline.validate("Data must be non-empty string") { event, _ ->
            event.incomingData is String && event.incomingData.isNotEmpty()
        }

        pipeline.intercept(DataPipelinePhases.Process) {
            if (!subject.isFailed()) {
                processExecuted = true
            }
            proceed()
        }

        pipeline.intercept(DataPipelinePhases.Fallback) {
            fallbackExecuted = true
        }

        val event = DataEvent("") // Invalid: empty string
        pipeline.execute(event)

        assertFalse(processExecuted, "Process should NOT execute for invalid data")
        assertTrue(fallbackExecuted, "Fallback SHOULD execute for failed events")
        assertTrue(event.isFailed())
        assertEquals("Data must be non-empty string", event.getError())
    }

    @Test
    fun `enrichment adds metadata to outgoingData`() = runBlocking {
        val pipeline = DataPipeline()

        pipeline.enrich { event, _ ->
            event.enrich("timestamp", System.currentTimeMillis())
            event.enrich("source", "api")
        }

        val event = DataEvent("data")
        pipeline.execute(event)

        assertNotNull(event.get<Long>("timestamp"))
        assertEquals("api", event.get<String>("source"))
    }
}