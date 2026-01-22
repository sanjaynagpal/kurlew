# CoroutineScope Design in DataPipeline

## The Question

**Why should DataPipeline extend CoroutineScope?**

Great question! This is a critical design decision about coroutine lifecycle management.

## The Answer

**DataPipeline MUST implement CoroutineScope** for proper structured concurrency and lifecycle management.

## Why It Matters

### 1. Structured Concurrency

Ktor's Pipeline expects the executing entity to manage coroutine lifecycle. By implementing `CoroutineScope`, DataPipeline becomes a proper coroutine scope owner with clear lifecycle boundaries.

```kotlin
// ✅ CORRECT - DataPipeline owns its coroutine scope
class DataPipeline(
    parentContext: CoroutineContext? = null
) : Pipeline<DataEvent, DataContext>(...), CoroutineScope {
    override val coroutineContext: CoroutineContext = 
        parentContext ?: (Dispatchers.Default + SupervisorJob())
}

// ❌ WRONG - No scope ownership
class DataPipeline : Pipeline<DataEvent, DataContext>(...) {
    // Where does the scope come from?
}
```

### 2. Lifecycle Management

With `CoroutineScope`, we can:
- **Cancel** the pipeline and all its operations
- **Check if active** before processing
- **Supervise** child coroutines properly
- **Clean up** resources on cancellation

```kotlin
val pipeline = DataPipeline()

// Process events
pipeline.execute(event)

// Later: cancel all operations
pipeline.cancel()

// Check if pipeline is still active
if (pipeline.isActive) {
    pipeline.execute(nextEvent)
}
```

### 3. Alignment with Ktor

Ktor's HTTP pipeline follows the same pattern:

```kotlin
// Ktor's ApplicationEngine implements CoroutineScope
class ApplicationEngine : CoroutineScope {
    override val coroutineContext: CoroutineContext
}

// Our DataPipeline should match this pattern
class DataPipeline : CoroutineScope {
    override val coroutineContext: CoroutineContext
}
```

## The Two CoroutineContexts

There are now **two** coroutine contexts in play:

### 1. Pipeline's Context (DataPipeline.coroutineContext)

**Purpose**: Controls the pipeline's lifecycle

```kotlin
val pipeline = DataPipeline(
    parentContext = Dispatchers.IO + SupervisorJob()
)

// This context controls:
// - Where pipeline operations run (Dispatchers.IO)
// - Lifecycle (SupervisorJob for supervision)
// - Cancellation scope
```

**Use cases**:
- Setting dispatcher for all pipeline operations
- Supervising all events processed by this pipeline
- Cancelling the entire pipeline

### 2. Event's Context (DataContext.coroutineContext)

**Purpose**: Controls individual event execution

```kotlin
val context = pipeline.contextBuilder()
    .correlationId("req-123")
    .build()

// If not specified, inherits from pipeline's context
pipeline.execute(event, context)
```

**Use cases**:
- Per-event dispatcher override
- Event-specific context elements
- Propagating context from caller

## How They Work Together

### Scenario 1: Default Behavior

```kotlin
val pipeline = DataPipeline()  // Uses Dispatchers.Default

// Event execution
pipeline.execute(event)  // Uses pipeline's context

// Flow:
// 1. Pipeline creates DataContext with its coroutineContext
// 2. Event processes in pipeline's context
// 3. All interceptors run in same context
```

### Scenario 2: Custom Pipeline Context

```kotlin
// Pipeline runs everything on IO dispatcher
val pipeline = DataPipeline(
    parentContext = Dispatchers.IO + SupervisorJob()
)

// All events use IO dispatcher by default
pipeline.execute(event1)
pipeline.execute(event2)
```

### Scenario 3: Per-Event Context

```kotlin
val pipeline = DataPipeline()  // Default context

// Event 1: Use pipeline's context
pipeline.execute(event1)

// Event 2: Use custom context
val customContext = pipeline.contextBuilder()
    .correlationId("special-req")
    .build()
    .copy(coroutineContext = Dispatchers.IO)
    
pipeline.execute(event2, customContext)  // Runs on IO
```

### Scenario 4: Context Inheritance

```kotlin
val pipeline = DataPipeline()

// Context without coroutineContext specified
val context = DataContext(
    correlationId = "req-123",
    coroutineContext = EmptyCoroutineContext  // Default
)

// Pipeline provides its context
pipeline.execute(event, context)
// -> Context is copied with pipeline's coroutineContext
```

## Implementation Details

### DataPipeline

```kotlin
class DataPipeline(
    parentContext: CoroutineContext? = null
) : Pipeline<DataEvent, DataContext>(...), CoroutineScope {
    
    // Pipeline's coroutine context
    override val coroutineContext: CoroutineContext = 
        parentContext ?: (Dispatchers.Default + SupervisorJob())
    
    // Execute with context merging
    suspend fun execute(event: DataEvent, context: DataContext): DataEvent {
        // If context doesn't have its own coroutineContext, use pipeline's
        val executionContext = if (context.coroutineContext == EmptyCoroutineContext) {
            context.copy(coroutineContext = coroutineContext)
        } else {
            context
        }
        
        execute(executionContext, event)
        return event
    }
    
    // Cancel pipeline
    fun cancel(cause: CancellationException? = null) {
        coroutineContext.cancel(cause)
    }
    
    // Check if active
    val isActive: Boolean
        get() = coroutineContext.isActive
}
```

### DataContext

```kotlin
data class DataContext(
    val correlationId: String = generateCorrelationId(),
    // ... other fields ...
    
    // Optional coroutine context (defaults to Empty)
    override val coroutineContext: CoroutineContext = EmptyCoroutineContext
) : CoroutineScope
```

## Usage Patterns

### Pattern 1: Long-Running Pipeline

```kotlin
// Create pipeline with supervised job
val pipeline = DataPipeline(
    parentContext = Dispatchers.Default + SupervisorJob()
)

// Process many events
launch {
    eventStream.collect { event ->
        pipeline.execute(event)
    }
}

// Shutdown: cancel pipeline and all its operations
pipeline.cancel()
```

### Pattern 2: Request-Scoped Pipeline

```kotlin
suspend fun handleRequest(request: Request) = coroutineScope {
    // Pipeline scoped to this request
    val pipeline = DataPipeline(
        parentContext = coroutineContext
    )
    
    val context = pipeline.contextBuilder()
        .correlationId(request.correlationId)
        .source(SourceContext.Http(...))
        .build()
    
    pipeline.execute(DataEvent(request.body), context)
    
    // Pipeline cancelled when coroutineScope ends
}
```

### Pattern 3: Test with Timeout

```kotlin
@Test
fun `test pipeline with timeout`() = runBlocking {
    val pipeline = DataPipeline()
    
    withTimeout(1000) {
        pipeline.execute(event)
    }
    
    // Pipeline's operations cancelled if timeout exceeded
}
```

### Pattern 4: Structured Concurrency

```kotlin
coroutineScope {
    val pipeline = DataPipeline(
        parentContext = coroutineContext  // Inherit parent
    )
    
    // Launch concurrent processing
    val job1 = launch { pipeline.execute(event1) }
    val job2 = launch { pipeline.execute(event2) }
    
    // Wait for both
    job1.join()
    job2.join()
    
    // If this scope cancels, pipeline cancels too
}
```

## Benefits Summary

### ✅ With CoroutineScope

1. **Proper Lifecycle**: Pipeline can be cancelled, checked for activity
2. **Structured Concurrency**: Follows Kotlin coroutines best practices
3. **Resource Cleanup**: Automatic cleanup on cancellation
4. **Supervision**: SupervisorJob prevents one failure from affecting all
5. **Dispatcher Control**: Control where pipeline operations run
6. **Ktor Alignment**: Matches Ktor's design patterns
7. **Testability**: Easier to test with controlled scopes

### ❌ Without CoroutineScope

1. **No Lifecycle Control**: Can't cancel or check if active
2. **Resource Leaks**: No automatic cleanup
3. **Unclear Ownership**: Who owns the coroutine scope?
4. **Testing Harder**: Can't control scope in tests
5. **Misaligned**: Doesn't follow Ktor patterns

## Comparison with Ktor

### Ktor HTTP Pipeline

```kotlin
// Ktor's ApplicationEngine
class NettyApplicationEngine : ApplicationEngine {
    override val coroutineContext: CoroutineContext
    
    fun start() {
        // Launch in engine's scope
        launch {
            // Process requests
        }
    }
    
    fun stop() {
        coroutineContext.cancel()
    }
}
```

### Our Data Pipeline

```kotlin
// Our DataPipeline (now matches Ktor's pattern)
class DataPipeline : CoroutineScope {
    override val coroutineContext: CoroutineContext
    
    suspend fun execute(event: DataEvent) {
        // Process in pipeline's scope
        execute(context, event)
    }
    
    fun cancel() {
        coroutineContext.cancel()
    }
}
```

## Migration Impact

### What Changed

**Before**:
```kotlin
class DataPipeline(
    private val defaultCoroutineContext: CoroutineContext = ...
) : Pipeline<DataEvent, DataContext>(...)
```

**After**:
```kotlin
class DataPipeline(
    parentContext: CoroutineContext? = null
) : Pipeline<DataEvent, DataContext>(...), CoroutineScope {
    override val coroutineContext: CoroutineContext = 
        parentContext ?: (Dispatchers.Default + SupervisorJob())
}
```

### For Users

**No breaking changes** for basic usage:

```kotlin
// Still works
val pipeline = DataPipeline()
pipeline.execute(event)
```

**New capabilities**:

```kotlin
// Can now cancel
pipeline.cancel()

// Can now check if active
if (pipeline.isActive) {
    pipeline.execute(event)
}

// Can now provide parent context
val pipeline = DataPipeline(
    parentContext = Dispatchers.IO + SupervisorJob()
)
```

## Best Practices

### 1. Use SupervisorJob for Production

```kotlin
val pipeline = DataPipeline(
    parentContext = Dispatchers.Default + SupervisorJob()
)
// SupervisorJob prevents one event failure from cancelling entire pipeline
```

### 2. Scope to Lifecycle

```kotlin
class MyService : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.Default
    
    private val pipeline = DataPipeline(parentContext = coroutineContext)
    
    fun shutdown() {
        coroutineContext.cancel()  // Cancels pipeline too
    }
}
```

### 3. Test with TestScope

```kotlin
@Test
fun test() = runTest {
    val pipeline = DataPipeline(
        parentContext = coroutineContext  // Use test scope
    )
    
    pipeline.execute(event)
}
```

### 4. Inherit Context When Possible

```kotlin
suspend fun process(event: DataEvent) = coroutineScope {
    val pipeline = DataPipeline(
        parentContext = coroutineContext  // Inherit caller's context
    )
    
    pipeline.execute(event)
}
```

## Conclusion

**DataPipeline MUST implement CoroutineScope** because:

1. ✅ Enables proper lifecycle management
2. ✅ Follows Kotlin coroutines best practices
3. ✅ Aligns with Ktor's design patterns
4. ✅ Provides cancellation and activity checking
5. ✅ Enables structured concurrency
6. ✅ Improves testability
7. ✅ Makes resource cleanup automatic

This is not just a nice-to-have—it's a fundamental requirement for proper coroutine-based architecture.