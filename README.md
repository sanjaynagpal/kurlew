# Kurlew Data Pipeline

A Ktor-inspired, multi-phase data processing pipeline with proper subject/context separation, built-in session management, distributed tracing, and natural backpressure.

[![Kotlin](https://img.shields.io/badge/kotlin-1.9.22-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Ktor](https://img.shields.io/badge/ktor-2.3.7-orange.svg)](https://ktor.io)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

## Features

✅ **Proper Subject/Context Separation**: DataEvent (what) vs DataContext (how)  
✅ **Built-in Session Management**: Track state across multiple events  
✅ **Service Registry**: Dependency injection for shared services  
✅ **Distributed Tracing**: Automatic correlation ID generation  
✅ **Source Tracking**: HTTP, WebSocket, Kafka, File, Database, Custom  
✅ **Built-in Caching**: Thread-safe cache with TTL support  
✅ **Natural Backpressure**: Automatic flow control via coroutine suspension  
✅ **Phase-Based Architecture**: Five mandatory phases  
✅ **High Performance**: >100K events/sec throughput, <1ms base overhead  
✅ **Ktor Integration**: Perfect alignment with Ktor's Pipeline design

## Quick Start

### Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.kurlew:data-pipeline:0.2.0")
}
```

### Basic Example

```kotlin
val pipeline = DataPipeline()

// Monitoring with error handling
pipeline.monitoringWrapper()

// Validation with context access
pipeline.validate { event, context ->
    event.incomingData is String && event.incomingData.isNotEmpty()
}

// Processing with service access
pipeline.process { event, context ->
    val db = context.services.getByType<Database>()
    db?.save(event.incomingData)
}

// Error handling with context
pipeline.onFailure { event, context ->
    logger.error("Failed ${context.correlationId}: ${event.getError()}")
}

// Execute with context
val context = pipeline.contextBuilder()
    .correlationId("req-123")
    .service("database", database)
    .build()

pipeline.execute(DataEvent(userData), context)
```

## Architecture

### Five Mandatory Phases

```
Acquire → Monitoring → Features → Process → Fallback
```

1. **Acquire**: Data ingestion and packaging into DataEvent
2. **Monitoring**: Exception detection and observability
3. **Features**: Validation and data enrichment
4. **Process**: Core business logic execution
5. **Fallback**: Error recovery and dead-letter queue

### DataEvent Model

```kotlin
data class DataEvent(
    val incomingData: Any,           // Immutable - thread safe
    val outgoingData: MutableMap<String, Any>  // Mutable - for enrichment
)
```

## Performance

### Benchmark Results

| Metric | Value |
|--------|-------|
| Base Overhead | <1ms per event |
| Throughput (single-threaded) | >10K events/sec |
| Throughput (concurrent) | >100K events/sec |
| p99 Latency | <15ms |
| Memory per Event | <1KB |
| Error Handling Overhead | 1.5x |

### Running Benchmarks

```bash
# Run all benchmarks
./gradlew benchmark

# Run specific benchmark
./gradlew test --tests "PipelineBenchmarks.benchmark - throughput"

# Run benchmark suite
./gradlew runBenchmarks
```

See [Benchmark Quick Start](docs/benchmark-quickstart.md) for detailed instructions.

## Documentation

### Core Documentation

- [Implementation Specification](docs/specification.md) - Complete technical specification
- [Design Decisions](docs/design.md) - Rationale behind key decisions
- [Ktor Usage Guide](docs/README_KTOR_USAGE.md) - How we leverage Ktor classes
- [finish() vs markFailed()](docs/finish-vs-markfailed.md) - Critical pattern guide

### Performance

- [Benchmark Report](docs/benchmark-report.md) - Detailed performance analysis
- [Benchmark Quick Start](docs/benchmark-quickstart.md) - Running and interpreting benchmarks

### Design References

- [Resilient Persistence and Backpressure](https://github.com/sanjaynagpal/go-learn/wiki/Design-Specification:-Resilient-Persistence-and-Backpressure-Management)
- [Architectural Overview](https://github.com/sanjaynagpal/go-learn/wiki/Ktor%E2%80%90Inspired-Data-Pipeline:-An-Architectural-Overview)
- [Architectural Design Specification](https://github.com/sanjaynagpal/go-learn/wiki/Ktor%E2%80%90Inspired-Data-Pipeline:-Architectural-Design-Specification)
- [Multi-Phase Journey](https://github.com/sanjaynagpal/go-learn/wiki/Understanding-the-Multi%E2%80%90Phase-Data-Pipeline:-A-Step%E2%80%90by%E2%80%90Step-Journey)

## Examples

### Simple Processing

```kotlin
val pipeline = DataPipeline()

pipeline.monitoringWrapper()
pipeline.validate { event -> event.incomingData is UserData }
pipeline.process { event ->
    val user = event.incomingData as UserData
    database.save(user)
}
pipeline.onFailure { event ->
    logger.error("Failed: ${event.getError()}")
}

pipeline.execute(DataEvent(userData))
```

### WebSocket Streaming with Backpressure

```kotlin
val pipeline = DataPipeline()

pipeline.monitoringWrapper()
pipeline.validate { event -> event.incomingData is ChatMessage }
pipeline.process { event ->
    delay(500) // Simulate slow processing
    database.save(event.incomingData)
}

// Natural backpressure via suspension
webSocket.incoming.consumeEach { frame ->
    val message = frame.readText()
    pipeline.execute(DataEvent(message))
    // Suspends here until message processed!
}
```

### Persistence with Retry

```kotlin
val pipeline = DataPipeline()

pipeline.monitoringWrapper()
pipeline.validate { event -> event.incomingData is OrderData }

// Retry logic
pipeline.intercept(DataPipelinePhases.Process) {
    var attempt = 0
    val maxAttempts = 3

    while (attempt < maxAttempts) {
        try {
            database.save(subject.incomingData)
            subject.enrich("saved", true)
            proceed()
            return@intercept
        } catch (e: Exception) {
            attempt++
            if (attempt < maxAttempts) delay(1.seconds)
        }
    }
    throw IllegalStateException("All retries exhausted")
}

pipeline.onFailure { event ->
    deadLetterQueue.send(event)
}
```

### Running Examples

```bash
# Simple example
./gradlew runSimpleExample

# WebSocket streaming example
./gradlew runWebSocketExample

# Persistence with retry example
./gradlew runPersistenceExample
```

## Building

```bash
# Build the project
./gradlew build

# Run tests (excludes benchmarks)
./gradlew test

# Run all tests including benchmarks
./gradlew test -Pbenchmark

# Generate documentation
./gradlew dokkaHtml
```

## Key Concepts

### Natural Backpressure

The pipeline's suspending `proceed()` mechanism creates automatic backpressure:

```kotlin
intercept(Acquire) {
    while (true) {
        val message = socket.receive()  // Fast producer
        proceedWith(DataEvent(message))  // SUSPENDS until processed
        // Won't fetch next message until current one completes!
    }
}
```

### Error Handling Pattern

Errors are detected in Monitoring and handled in Fallback:

```kotlin
// Monitoring detects errors
intercept(Monitoring) {
    try {
        proceed()
    } catch (e: Exception) {
        subject.markFailed(e.message)
        proceed()  // Let Fallback handle it
    }
}

// Fallback handles errors
intercept(Fallback) {
    if (subject.isFailed()) {
        deadLetterQueue.send(subject)
    }
}
```

### Validation Short-Circuiting

Invalid data is rejected early to save resources:

```kotlin
intercept(Features) {
    if (!isValid(subject.incomingData)) {
        subject.markFailed("Invalid data")
        // Don't call finish() - let Fallback handle it
    }
    proceed()
}

intercept(Process) {
    // Only executes for valid data
    if (!subject.isFailed()) {
        database.save(subject.incomingData)
    }
    proceed()
}
```

## API Reference

### Extension Functions

| Function | Purpose |
|----------|---------|
| `monitoringWrapper()` | Add error handling to Monitoring phase |
| `validate(predicate)` | Add validation to Features phase |
| `enrich(enricher)` | Add enrichment to Features phase |
| `process(processor)` | Add processing to Process phase |
| `onFailure(handler)` | Handle failed events in Fallback |
| `onSuccess(handler)` | Handle successful events in Fallback |

### DataEvent Methods

| Method | Purpose |
|--------|---------|
| `isFailed()` | Check if event marked as failed |
| `markFailed(error)` | Mark event as failed |
| `getError()` | Retrieve error message |
| `enrich(key, value)` | Add enrichment data |
| `get<T>(key)` | Retrieve enriched data |

## Best Practices

### ✅ Do

- Use `markFailed()` for validation failures (not `finish()`)
- Always add `monitoringWrapper()` for error handling
- Validate early in Features phase
- Keep Process phase focused on business logic
- Handle all failures in Fallback phase

### ❌ Don't

- Don't call `finish()` for validation failures (skips Fallback)
- Don't modify `incomingData` (it's immutable)
- Don't catch exceptions in Process (let Monitoring handle it)
- Don't skip phases in your pipeline design
- Don't process failed events in Process phase

## Contributing

Contributions are welcome! Please read our [Contributing Guide](CONTRIBUTING.md) before submitting PRs.

### Development Setup

```bash
# Clone repository
git clone https://github.com/sanjaynagpal/kurlew.git
cd kurlew

# Build
./gradlew build

# Run tests
./gradlew test

# Run benchmarks
./gradlew benchmark
```

## License

Apache License 2.0 - see [LICENSE](LICENSE) file for details.

## Acknowledgments

- Built on top of [Ktor's Pipeline infrastructure](https://ktor.io)
- Inspired by design specifications from [go-learn repository](https://github.com/sanjaynagpal/go-learn/wiki)
- Leverages [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) for concurrency

## Support

- 📖 [Documentation](docs/)
- 🐛 [Issue Tracker](https://github.com/sanjaynagpal/kurlew/issues)
- 💬 [Discussions](https://github.com/sanjaynagpal/kurlew/discussions)

## Roadmap

- [ ] Metrics integration (Micrometer, Prometheus)
- [ ] Distributed tracing (OpenTelemetry)
- [ ] Configuration DSL
- [ ] Plugin system
- [ ] Reactive Streams integration
- [ ] Performance optimizations
- [ ] Additional examples and tutorials