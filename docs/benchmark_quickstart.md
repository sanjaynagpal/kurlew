# Benchmark Quick Start Guide

## Running Benchmarks

### Run All Benchmarks

```bash
./gradlew benchmark
```

This will run all 10 benchmark tests with optimized JVM settings.

### Run Specific Benchmark

```bash
# Run only throughput benchmark
./gradlew test --tests "PipelineBenchmarks.benchmark - realistic pipeline throughput"

# Run only latency benchmark
./gradlew test --tests "PipelineBenchmarks.benchmark - latency percentiles"

# Run only overhead benchmark
./gradlew test --tests "PipelineBenchmarks.benchmark - minimal pipeline overhead"
```

### Run Benchmark Suite via BenchmarkRunner

```bash
./gradlew runBenchmarks
```

This runs all benchmarks sequentially through the standalone runner.

## Understanding the Output

### Example Output

```
=== Benchmark: Minimal Pipeline Overhead ===

Minimal Pipeline Statistics:
  Iterations: 10000
  Average: 0.421ms
  Min: 0ms
  Max: 3ms
  p50: 0ms
  p95: 1ms
  p99: 2ms

✓ Average overhead: 0.421ms per event
```

### Key Metrics Explained

- **Average**: Mean execution time across all iterations
- **Min/Max**: Fastest and slowest execution times
- **p50 (median)**: 50% of executions completed in this time or less
- **p95**: 95% of executions completed in this time or less
- **p99**: 99% of executions completed in this time or less

### What to Look For

✅ **Good Performance**:
- Average < 1ms for minimal overhead
- p99 < 2x average
- Throughput > 10K events/sec

⚠️ **Performance Issues**:
- Average > 2ms for minimal overhead
- p99 > 5x average
- High variance (max >> p99)

## Customizing Benchmarks

### Adjust Iteration Count

Edit `PipelineBenchmarks.kt`:

```kotlin
companion object {
    const val WARMUP_ITERATIONS = 5000    // More warmup
    const val BENCHMARK_ITERATIONS = 50000  // More iterations
}
```

### Adjust JVM Settings

Edit `build.gradle.kts`:

```kotlin
tasks.register<Test>("benchmark") {
    jvmArgs(
        "-Xms8g",      // Increase heap
        "-Xmx8g",
        "-XX:+UseZGC"  // Use ZGC for low latency
    )
}
```

### Add Custom Benchmark

1. Add test method to `PipelineBenchmarks.kt`:

```kotlin
@org.junit.jupiter.api.Test
fun `benchmark - my custom test`() = runBlocking {
    println("\n=== Benchmark: My Custom Test ===")
    
    val pipeline = DataPipeline()
    // Configure pipeline...
    
    // Warmup
    repeat(WARMUP_ITERATIONS) {
        pipeline.execute(DataEvent("warmup"))
    }
    
    // Benchmark
    val totalTime = measureTimeMillis {
        repeat(BENCHMARK_ITERATIONS) {
            pipeline.execute(DataEvent("data-$it"))
        }
    }
    
    val throughput = (BENCHMARK_ITERATIONS.toDouble() / totalTime) * 1000
    println("Throughput: ${String.format("%.2f", throughput)} events/sec")
}
```

## Benchmark Best Practices

### 1. Warm Up the JVM

Always include warmup iterations to allow JIT compilation:

```kotlin
// Warmup
repeat(1000) {
    pipeline.execute(DataEvent("warmup"))
}

// Now measure
val time = measureTimeMillis {
    repeat(10000) {
        pipeline.execute(DataEvent("data-$it"))
    }
}
```

### 2. Minimize External Factors

- Close other applications
- Run on consistent hardware
- Disable CPU frequency scaling
- Run multiple times and average results

### 3. Use Realistic Data

Use representative data sizes and types:

```kotlin
// Bad - not realistic
DataEvent("x")

// Good - realistic size
DataEvent(UserRequest(
    userId = 12345,
    action = "purchase",
    items = listOf("item1", "item2", "item3")
))
```

### 4. Measure What Matters

Focus on metrics relevant to your use case:

- **Batch processing**: Throughput (events/sec)
- **API endpoints**: Latency (p99)
- **Streaming**: Backpressure behavior
- **Error handling**: Error path overhead

## Interpreting Results

### Throughput Benchmarks

**Single-threaded**:
- Excellent: >10K events/sec
- Good: 5-10K events/sec
- Poor: <5K events/sec

**Concurrent**:
- Excellent: >100K events/sec
- Good: 50-100K events/sec
- Poor: <50K events/sec

### Latency Benchmarks

**p99 Latency**:
- Excellent: <5ms
- Good: 5-15ms
- Acceptable: 15-50ms
- Poor: >50ms

### Overhead Benchmarks

**Pipeline Base Overhead**:
- Excellent: <0.5ms
- Good: 0.5-1.0ms
- Acceptable: 1.0-2.0ms
- Poor: >2.0ms

## Comparing Results

### Baseline Measurements

Run benchmarks on reference hardware to establish baselines:

```bash
# Generate baseline report
./gradlew benchmark > baseline-results.txt

# Compare later runs
./gradlew benchmark > current-results.txt
diff baseline-results.txt current-results.txt
```

### Regression Detection

Watch for:
- Throughput drops >10%
- Latency increases >20%
- Memory usage increases >25%

## Continuous Benchmarking

### CI/CD Integration

Add to your CI pipeline:

```yaml
# .github/workflows/benchmarks.yml
name: Performance Benchmarks

on:
  push:
    branches: [main, develop]
  pull_request:

jobs:
  benchmark:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
      - name: Run benchmarks
        run: ./gradlew benchmark
      - name: Upload results
        uses: actions/upload-artifact@v3
        with:
          name: benchmark-results
          path: build/reports/tests/benchmark/
```

### Performance Tracking

Track metrics over time:

1. Store benchmark results after each run
2. Plot trends (throughput, latency)
3. Alert on regressions
4. Compare branches/commits

## Troubleshooting

### Benchmarks Taking Too Long

Reduce iterations:

```kotlin
const val BENCHMARK_ITERATIONS = 1000  // Down from 10000
```

### Inconsistent Results

- Increase warmup iterations
- Run on dedicated hardware
- Disable power management
- Close background applications

### Out of Memory Errors

Increase heap size in `build.gradle.kts`:

```kotlin
jvmArgs("-Xms8g", "-Xmx8g")
```

### JVM Not Optimizing

Ensure sufficient warmup and use `-server` flag:

```kotlin
jvmArgs("-server")
```

## Advanced Benchmarking

### JMH Integration

For production-grade benchmarks, consider JMH:

```kotlin
dependencies {
    testImplementation("org.openjdk.jmh:jmh-core:1.37")
    testAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}
```

### Profiling

Use profilers to identify bottlenecks:

```bash
# VisualVM
jvisualvm

# Async-profiler
./profiler.sh -d 30 -f flamegraph.html <pid>

# JFR
./gradlew benchmark -Djvm.args="-XX:StartFlightRecording=duration=60s,filename=recording.jfr"
```

### Memory Profiling

Track allocations:

```bash
./gradlew benchmark -Djvm.args="-XX:+PrintGCDetails -XX:+PrintGCDateStamps"
```

## Resources

- [JVM Performance Tuning Guide](https://docs.oracle.com/en/java/javase/17/gctuning/)
- [Kotlin Coroutines Performance](https://kotlinlang.org/docs/coroutines-guide.html#performance)
- [JMH Samples](https://github.com/openjdk/jmh)

## Getting Help

If you encounter issues:

1. Check [troubleshooting section](#troubleshooting)
2. Review [benchmark report](./benchmark-report.md)
3. Open an issue with:
   - Hardware specs
   - JVM version
   - Benchmark output
   - Expected vs actual results