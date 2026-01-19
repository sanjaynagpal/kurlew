# Critical: Understanding finish() vs markFailed()

## The Problem

There's a critical distinction between two patterns for handling invalid data in the Features phase:

### ❌ Pattern 1: Using finish() (WRONG for validation failures)

```kotlin
pipeline.intercept(DataPipelinePhases.Features) {
    if (!isValid(subject.incomingData)) {
        subject.markFailed("Validation failed")
        finish() // ⚠️ STOPS ENTIRE PIPELINE
        return@intercept
    }
    proceed()
}

pipeline.onFailure { event ->
    deadLetterQueue.send(event) // ❌ NEVER EXECUTES!
}
```

**Problem**: `finish()` stops the **entire pipeline**, including the Fallback phase. Your DLQ handler never runs, and you **lose failed events**.

**Execution Flow**:
```
Acquire → Monitoring → Features (finish() called) → ❌ STOPPED
                                                    ↓
                                              Fallback NEVER RUNS
                                              DLQ NEVER CALLED
```

### ✅ Pattern 2: Using markFailed() only (CORRECT)

```kotlin
pipeline.intercept(DataPipelinePhases.Features) {
    if (!isValid(subject.incomingData)) {
        subject.markFailed("Validation failed")
        // Don't call finish() - just skip to end of interceptor
    } else {
        // Only enrich if valid
        subject.enrich("validated", true)
    }
    proceed() // Always proceed to allow Fallback to handle failures
}

pipeline.process { event ->
    // Process extension already checks isFailed()
    database.save(event.incomingData)
}

pipeline.onFailure { event ->
    deadLetterQueue.send(event) // ✅ EXECUTES for failed events
}
```

**Execution Flow**:
```
Acquire → Monitoring → Features (marked failed) → Process (skipped if failed) → Fallback (handles failure)
                                                                                    ↓
                                                                              DLQ CALLED ✓
```

## When to Use Each

### Use `finish()` for:
- **Successful early termination** (e.g., cache hit)
- **Optimization** when no further processing needed
- **Not an error** - just done early

```kotlin
intercept(Features) {
    val cached = cache.get(subject.incomingData)
    if (cached != null) {
        subject.enrich("fromCache", true)
        finish() // Success - no need for Process or Fallback
        return@intercept
    }
    proceed()
}
```

### Use `markFailed()` (without finish) for:
- **Validation failures**
- **Any error that should be logged/tracked**
- **Events that need DLQ handling**

```kotlin
intercept(Features) {
    if (!isValid(subject.incomingData)) {
        subject.markFailed("Validation error")
        // No finish() - let Fallback handle it
    }
    proceed()
}
```

## How Our Extension Functions Handle This

### The `validate()` Extension (Corrected)

```kotlin
fun DataPipeline.validate(
    errorMessage: String = "Validation failed",
    predicate: (DataEvent) -> Boolean
) {
    intercept(DataPipelinePhases.Features) {
        if (!predicate(subject)) {
            subject.markFailed(errorMessage)
            // No finish() - allows Fallback to execute
        } else {
            subject.enrich("validated", true)
        }
        proceed() // Always proceed
    }
}
```

### The `process()` Extension (Protects Process Phase)

```kotlin
fun DataPipeline.process(
    processor: suspend (DataEvent) -> Unit
) {
    intercept(DataPipelinePhases.Process) {
        // Only process if not already failed
        if (!subject.isFailed()) {
            processor(subject)
        }
        proceed() // Always proceed to Fallback
    }
}
```

## Complete Working Example

```kotlin
val pipeline = DataPipeline()
val failedEvents = mutableListOf<DataEvent>()

// Monitoring
pipeline.monitoringWrapper()

// Validation - marks failed but doesn't stop pipeline
pipeline.validate { event ->
    event.incomingData is String && event.incomingData.length > 0
}

// Processing - automatically skips if failed
pipeline.process { event ->
    database.save(event.incomingData)
}

// Fallback - handles all failed events
pipeline.onFailure { event ->
    failedEvents.add(event) // ✅ This WILL execute
    deadLetterQueue.send(event)
}

// Execute with invalid data
val event = pipeline.executeRaw("") // Empty string - invalid

// Assertions
assertTrue(event.isFailed())
assertEquals(1, failedEvents.size) // ✅ PASSES
```

## Summary Table

| Scenario | Pattern | Fallback Executes? | Use Case |
|----------|---------|-------------------|----------|
| Validation failure | `markFailed()` + `proceed()` | ✅ Yes | DLQ, logging, error tracking |
| Cache hit | `finish()` | ❌ No | Optimization, early success |
| Processing exception | Caught by Monitoring | ✅ Yes | Error handling |
| Invalid data | `markFailed()` + `proceed()` | ✅ Yes | Data quality issues |
| Circuit breaker open | `finish()` | ❌ No | Load shedding |

## Key Takeaway

**For validation failures and any errors you want to track/log/DLQ:**
- ✅ Use `markFailed()`
- ✅ Call `proceed()` to reach Fallback
- ❌ DON'T call `finish()`

**For successful early termination:**
- ✅ Use `finish()`
- ❌ DON'T mark as failed

This ensures your Fallback phase and DLQ handlers always execute for actual failures!