# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kurlew is a Ktor-inspired, multi-phase data processing pipeline for Kotlin. It provides resilient data handling with natural backpressure and comprehensive error handling. The pipeline is built on top of Ktor's Pipeline infrastructure and leverages Kotlin coroutines for concurrency.

## Common Commands

### Building and Testing
```bash
# Build the project
./gradlew build

# Run tests (excludes benchmarks)
./gradlew test

# Run all tests including benchmarks
./gradlew test -Pbenchmark

# Run only benchmarks
./gradlew benchmark

# Run all benchmarks via BenchmarkRunner
./gradlew runBenchmarks

# Generate benchmark report
./gradlew benchmarkReport
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

### Development
```bash
# Run a specific test
./gradlew test --tests "DataPipelineTest"

# Run a specific benchmark
./gradlew test --tests "PipelineBenchmarks.benchmark - throughput" -Pbenchmark
```

## Architecture

### Core Concepts

**Five Mandatory Phases** (always executed in order):
1. **Setup**: Data ingestion and packaging into DataEvent
2. **Monitoring**: Exception detection and observability (wraps downstream phases)
3. **Features**: Validation and data enrichment
4. **Process**: Core business logic execution
5. **Fallback**: Error recovery and dead-letter queue handling

**Key Classes**:
- `DataPipeline`: Main orchestrator, extends `Pipeline<DataEvent, DataEvent>`, implements `CoroutineScope`
- `DataEvent`: Context object with immutable `incomingData: Any` and mutable `outgoingData: MutableMap<String, Any>`
- `DataPipelinePhases`: Defines the 5 singleton `PipelinePhase` objects
- `StartEvent`: Sealed class for acquisition modes (ByMic, ByTopicPartition, Once, Resume, Custom)

**Extension Functions** (in `DataPipelineExtensions.kt`):
- `monitoringWrapper()`: Add error handling to Monitoring phase
- `validate()`: Add validation to Features phase
- `enrich()`: Add enrichment to Features phase
- `process()`: Add processing to Process phase
- `onFailure()`: Handle failed events in Fallback
- `onSuccess()`: Handle successful events in Fallback
- `retry()`: Add retry logic with exponential backoff

### Architectural Insights

**DataPipeline IS the Orchestrator**: There is no separate `DataOrchestrator` class. The `DataPipeline` class extends Ktor's `Pipeline<DataEvent, DataEvent>`, which provides the orchestration mechanism. The pipeline manages phase execution, interceptor chains, and the `proceed()` mechanics.

**Immutable Source, Mutable State**: `DataEvent.incomingData` is immutable and serves as the source of truth. `DataEvent.outgoingData` is mutable and accumulates state across phases. This CQRS-like pattern enables debugging and auditing via immutable input trails.

**Fail-Forward Error Handling**: Failed events don't short-circuit; they proceed through all phases to Fallback. This ensures all error paths are observable and recoverable:
- Exceptions in Process → caught by Monitoring → event marked failed → proceeds to Fallback
- Validation failures in Features → event marked failed → continues through Process (which skips) → reaches Fallback
- Fallback phase splits into `onFailure()` and `onSuccess()` terminal handlers

**Natural Backpressure**: The pipeline's suspending `execute()` function creates automatic backpressure. When processing is slow, the caller is suspended until the event completes, preventing overwhelming the pipeline.

### Critical Pattern: `finish()` vs `markFailed()`

**DO NOT use `finish()` for validation failures or errors**. Using `finish()` terminates pipeline execution immediately, skipping Fallback phase where DLQ and error handling occur.

**Correct Pattern**:
```kotlin
pipeline.intercept(DataPipelinePhases.Features) {
    if (!isValid(subject.incomingData)) {
        subject.markFailed("Invalid data")
        // DON'T call finish() - let event proceed to Fallback
    }
    proceed()
}
```

**Use `markFailed()`** to mark events as failed while allowing them to proceed through remaining phases. Only use `finish()` for early termination of successful events (rare).

See `docs/finish-vs-markfailed.md` for detailed explanation.

## Code Organization

```
src/
├── main/kotlin/io/kurlew/
│   ├── pipeline/
│   │   ├── DataPipeline.kt           # Core pipeline class
│   │   ├── DataEvent.kt              # Context object
│   │   ├── DataPipelinePhases.kt     # 5 phase definitions
│   │   ├── StartEvent.kt             # Acquisition modes
│   │   └── extensions/
│   │       └── DataPipelineExtensions.kt  # DSL-like helpers
│   └── examples/
│       ├── SimpleDataPipelineExample.kt
│       ├── PersistenceWithRetryExample.kt
│       ├── WebSocketStreamingExample.kt
│       └── Main.kt
└── test/kotlin/io/kurlew/pipeline/
    ├── DataPipelineTest.kt           # Core pipeline tests
    ├── MonitoringPhaseTest.kt        # Monitoring phase tests
    ├── FeaturesPhaseTest.kt          # Features phase tests
    ├── ProcessPhaseTest.kt           # Process phase tests
    ├── FallbackPhaseTest.kt          # Fallback phase tests
    ├── integration/
    │   └── EndToEndPipelineTest.kt   # Full pipeline integration
    └── benchmarks/
        ├── PipelineBenchmarks.kt     # 10 comprehensive benchmarks
        └── BenchmarkRunner.kt        # Standalone benchmark runner
```

## Key Dependencies

- Ktor Utils 2.3.7: Provides `Pipeline` infrastructure
- Kotlin Coroutines 1.7.3: Enables suspension-based processing
- JUnit 5: Testing and benchmark framework
- JVM Toolchain: Java 24

## Testing Strategy

**Unit Tests**: Each phase has dedicated tests verifying isolated behavior.

**Integration Tests**: `EndToEndPipelineTest` validates full pipeline flow with valid/invalid requests and exception handling.

**Benchmarks**: 10 comprehensive benchmarks measuring:
- Minimal pipeline overhead (<1ms base)
- Realistic throughput (>10K events/sec single-threaded, >100K concurrent)
- Short-circuit optimization (>5x speedup for early failures)
- Latency percentiles (p50, p95, p99)
- Error handling overhead
- Memory allocation
- Backpressure mechanism
- Multiple interceptors overhead
- Data enrichment overhead

Benchmarks are isolated from regular tests and run with optimized JVM settings (`-Xms4g -Xmx4g -XX:+UseG1GC -server`).

## Design Patterns

**Extension Function DSL**: Prefer using extension functions (`validate()`, `enrich()`, `process()`) over direct `intercept()` calls for common patterns. Extension functions handle boilerplate and enforce best practices.

**Composable Interceptors**: Multiple interceptors can be added to the same phase. For example, multiple `validate()`, `enrich()`, or `process()` calls are composed in registration order.

**3-Layer Pattern** (typical usage):
1. `monitoringWrapper()` → wraps all downstream in try-catch
2. `validate()` + `enrich()` → validates and enriches data
3. `process()` → executes business logic
4. `onFailure()`/`onSuccess()` → terminal handlers in Fallback

**DLQ Pattern**: Use `onFailure()` in Fallback phase to route failed events to dead-letter queue:
```kotlin
val deadLetterQueue = mutableListOf<DataEvent>()
pipeline.onFailure { event ->
    deadLetterQueue.add(event)
}
```

**Retry Pattern**: Use `retry()` extension or custom retry logic in Process phase with exponential backoff. See `PersistenceWithRetryExample.kt`.

## Important Documentation

- `docs/specification.md`: Complete technical specification
- `docs/design.md`: Rationale behind key decisions
- `docs/README_KTOR_USAGE.md`: How Ktor classes are leveraged
- `docs/finish-vs-markfailed.md`: Critical pattern guide
- `docs/benchmark-report.md`: Detailed performance analysis
- `docs/benchmark-quickstart.md`: Running and interpreting benchmarks

## Design References

External wiki documentation (sanjaynagpal/go-learn):
- Resilient Persistence and Backpressure Management
- Architectural Overview
- Architectural Design Specification
- Multi-Phase Data Pipeline Journey
