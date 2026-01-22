# DataContext Architecture: Subject vs Context Separation

## Executive Summary

This document describes a significant architectural improvement to the Data Pipeline: the separation of **DataEvent** (subject) from **DataContext** (context). This change aligns the implementation more closely with Ktor's design patterns and enables powerful new capabilities.

## Architectural Change

### Previous Architecture

```kotlin
// OLD: DataEvent served dual purpose
class DataPipeline : Pipeline<DataEvent, DataEvent>

// Both subject and context were the same object
intercept(Phase) {
    subject.incomingData  // The data
    subject.outgoingData  // Also the context
}
```

**Limitations**:
- Mixed concerns: data and execution environment were coupled
- No way to share services across events
- No session management
- No source tracking
- Limited extensibility

### New Architecture

```kotlin
// NEW: Proper separation
class DataPipeline : Pipeline<DataEvent, DataContext>

// Subject = what is being processed
// Context = how to process it
intercept(Phase) {
    subject.incomingData   // DataEvent: the data
    context.correlationId  // DataContext: execution environment
    context.cache          // Shared cache
    context.services       // Shared services
    context.session        // Session state
}
```

**Benefits**:
- Clear separation of concerns
- Session management across events
- Shared services and cache
- Source context tracking
- Distributed tracing support
- Better testability
- Aligns with Ktor's HTTP pipeline design

## Core Components

### 1. DataEvent (TSubject)

**Purpose**: Represents the data being processed.

**Structure**:
```kotlin
data class DataEvent(
    val incomingData: Any,                     // Immutable source data
    val outgoingData: MutableMap<String, Any>  // Mutable enrichment
)
```

**Responsibilities**:
- Carry the original input data (immutable)
- Accumulate enrichment metadata (mutable)
- Track failure state
- Provide type-safe data access

**Example**:
```kotlin
val event = DataEvent(
    incomingData = UserRequest(userId = 123, action = "login")
)

event.enrich("timestamp", System.currentTimeMillis())
event.enrich("validated", true)
```

### 2. DataContext (TContext)

**Purpose**: Provides the execution environment for processing.

**Structure**:
```kotlin
data class DataContext(
    val correlationId: String,          // Tracing ID
    val source: SourceContext?,         // Where event came from
    val session: SessionContext?,       // Session state
    val cache: CacheContext,            // Temporary storage
    val services: ServiceRegistry,      // Shared services
    val attributes: MutableMap<...>,    // Custom attributes
    val coroutineContext: CoroutineContext
)
```

**Responsibilities**:
- Track event origin (HTTP, WebSocket, Kafka, etc.)
- Maintain session state across events
- Provide shared cache for lookups
- Manage service dependencies (DB, APIs)
- Enable distributed tracing
- Store custom execution attributes

**Example**:
```kotlin
val context = pipeline.contextBuilder()
    .correlationId("req-12345")
    .source(SourceContext.Http(
        method = "POST",
        uri = "/api/users"
    ))
    .session(SessionContext("session-abc"))
    .service("database", databaseConnection)
    .build()
```

## Key Sub-Components

### SourceContext

Tracks where the data event originated:

```kotlin
sealed class SourceContext {
    data class Http(method: String, uri: String, ...)
    data class WebSocket(connectionId: String, ...)
    data class MessageQueue(topic: String, partition: Int, ...)
    data class FileSystem(path: String, ...)
    data class Database(tableName: String, operation: String, ...)
    data class Custom(type: String, metadata: Map<...>)
}
```

**Use Cases**:
- Audit trails
- Source-specific processing rules
- Routing decisions
- Error reporting with context

### SessionContext

Maintains state across multiple events:

```kotlin
data class SessionContext(
    val sessionId: String,
    val data: MutableMap<String, Any>,
    val createdAt: Long,
    var lastActivityAt: Long
)
```

**Use Cases**:
- User sessions in web applications
- Connection state for WebSockets
- Transaction context
- Multi-step workflows

**Example**:
```kotlin
val session = SessionContext("session-123")

// First event
session.set("userId", 42)
session.set("loginTime", System.currentTimeMillis())

// Later event (same session)
val userId = session.get<Int>("userId")
```

### CacheContext

Thread-safe temporary storage with TTL support:

```kotlin
class CacheContext {
    fun put(key: String, value: Any, ttlMs: Long?)
    fun get<T>(key: String): T?
    fun getOrPut<T>(key: String, ttlMs: Long?, defaultValue: () -> T): T
}
```

**Use Cases**:
- Lookup table caching
- Expensive computation results
- API response caching
- Validation rule storage

**Example**:
```kotlin
// Cache user data for 5 minutes
context.cache.put("user:123", userData, ttlMs = 300_000)

// Retrieve from cache
val cached = context.cache.get<UserData>("user:123")
```

### ServiceRegistry

Dependency injection for shared services:

```kotlin
class ServiceRegistry {
    fun register<T : Any>(name: String, service: T)
    fun get<T>(name: String): T?
    fun getByType<T : Any>(): T?
}
```

**Use Cases**:
- Database connections
- API clients
- Message queue producers
- Configuration objects

**Example**:
```kotlin
// Register services
context.services.register("database", dbConnection)
context.services.register("userApi", userApiClient)

// Access in interceptor
val db = context.services.getByType<Database>()
db?.save(event.incomingData)
```

## Comparison: Old vs New

### Interceptor Signature

**Old**:
```kotlin
intercept(Phase) {
    // subject and context are same object
    subject.incomingData
    subject.outgoingData
}
```

**New**:
```kotlin
intercept(Phase) {
    // subject = data, context = environment
    subject.incomingData    // DataEvent
    context.correlationId   // DataContext
    context.cache           // Shared cache
    context.services        // Shared services
}
```

### Extension Function Signatures

**Old**:
```kotlin
fun DataPipeline.validate(
    predicate: (DataEvent) -> Boolean
)

fun DataPipeline.process(
    processor: suspend (DataEvent) -> Unit
)
```

**New**:
```kotlin
fun DataPipeline.validate(
    predicate: (DataEvent, DataContext) -> Boolean
)

fun DataPipeline.process(
    processor: suspend (DataEvent, DataContext) -> Unit
)
```

### Execution

**Old**:
```kotlin
pipeline.execute(DataEvent(data))
```

**New**:
```kotlin
// With default context
pipeline.execute(DataEvent(data))

// With custom context
val context = pipeline.contextBuilder()
    .correlationId("req-123")
    .source(SourceContext.Http(...))
    .build()
    
pipeline.execute(DataEvent(data), context)
```

## Use Case Examples

### 1. HTTP Request Processing

```kotlin
// Build context from HTTP request
val context = pipeline.contextBuilder()
    .correlationId(request.headers["X-Correlation-ID"] ?: generateId())
    .source(SourceContext.Http(
        method = request.method,
        uri = request.uri,
        headers = request.headers.toMap(),
        remoteAddress = request.remoteAddress
    ))
    .service("database", database)
    .build()

// Process with full HTTP context
pipeline.execute(DataEvent(request.body), context)
```

### 2. WebSocket Connection

```kotlin
// Create session for WebSocket connection
val session = SessionContext("ws-${connectionId}")

// Process each message with same session
webSocket.incoming.consumeEach { frame ->
    val context = pipeline.contextBuilder()
        .correlationId("${connectionId}-${messageId++}")
        .source(SourceContext.WebSocket(
            connectionId = connectionId,
            uri = webSocket.uri
        ))
        .session(session)  // Same session across messages
        .build()
    
    pipeline.execute(DataEvent(frame.readText()), context)
}
```

### 3. Kafka Message Processing

```kotlin
kafkaConsumer.subscribe(listOf("events"))

kafkaConsumer.poll().forEach { record ->
    val context = pipeline.contextBuilder()
        .correlationId(record.headers.get("correlationId"))
        .source(SourceContext.MessageQueue(
            topic = record.topic,
            partition = record.partition,
            offset = record.offset,
            key = record.key
        ))
        .service("database", database)
        .build()
    
    pipeline.execute(DataEvent(record.value), context)
}
```

### 4. Cached Lookups

```kotlin
pipeline.enrich { event, context ->
    // Try cache first
    val userData = context.cache.getOrPut("user:${userId}", ttlMs = 60_000) {
        // Cache miss - fetch from database
        val db = context.services.getByType<Database>()
        db.getUserData(userId)
    }
    
    event.enrich("userName", userData.name)
}
```

### 5. Distributed Tracing

```kotlin
pipeline.monitoringWrapper { event, context ->
    // Start trace span
    val tracer = context.services.getByType<Tracer>()
    tracer?.startSpan(
        traceId = context.correlationId,
        spanName = "pipeline.execute",
        metadata = mapOf(
            "source" to context.source.toString(),
            "eventType" to event.incomingData::class.simpleName
        )
    )
}
```

## Migration Guide

### Step 1: Update Interceptor Signatures

**Before**:
```kotlin
pipeline.intercept(Features) {
    val data = subject.incomingData
    // Process...
    proceed()
}
```

**After**:
```kotlin
pipeline.intercept(Features) {
    val data = subject.incomingData
    val traceId = context.correlationId  // NEW: Access context
    // Process...
    proceed()
}
```

### Step 2: Update Extension Function Calls

**Before**:
```kotlin
pipeline.validate { event ->
    event.incomingData is String
}
```

**After**:
```kotlin
pipeline.validate { event, context ->
    event.incomingData is String
}
```

### Step 3: Add Context When Executing

**Before**:
```kotlin
pipeline.execute(DataEvent(data))
```

**After**:
```kotlin
// Option 1: Default context
pipeline.execute(DataEvent(data))

// Option 2: Custom context
val context = pipeline.contextBuilder()
    .correlationId("req-123")
    .build()
pipeline.execute(DataEvent(data), context)
```

### Step 4: Leverage New Capabilities

```kotlin
// Add services
val context = pipeline.contextBuilder()
    .service("database", database)
    .service("cache", redisCache)
    .build()

// Use in interceptors
pipeline.process { event, context ->
    val db = context.services.getByType<Database>()
    db?.save(event.incomingData)
}
```

## Benefits Recap

### 1. Better Separation of Concerns
- Data (DataEvent) is separate from environment (DataContext)
- Clear responsibilities for each component
- Easier to understand and maintain

### 2. Session Management
- Maintain state across multiple events
- Support for user sessions, connection state
- Clean API for session data

### 3. Shared Services
- Inject dependencies once, use everywhere
- Database connections, API clients, etc.
- Better testability with mock services

### 4. Source Tracking
- Know where each event came from
- Different processing rules per source
- Better audit trails and debugging

### 5. Distributed Tracing
- Correlation IDs for tracking
- Integration with tracing systems
- End-to-end request tracking

### 6. Caching
- Built-in cache with TTL support
- Share expensive lookups across phases
- Thread-safe concurrent access

### 7. Extensibility
- Easy to add new context attributes
- Custom source types
- Plugin-friendly architecture

## Alignment with Ktor

This architecture now matches Ktor's HTTP pipeline design:

| Ktor HTTP | Data Pipeline |
|-----------|---------------|
| `Pipeline<Unit, ApplicationCall>` | `Pipeline<DataEvent, DataContext>` |
| `ApplicationCall` (context) | `DataContext` (context) |
| `ApplicationRequest` (incoming) | `DataEvent.incomingData` |
| `ApplicationResponse` (outgoing) | `DataEvent.outgoingData` |
| `call.attributes` | `context.attributes` |

This alignment makes the Data Pipeline immediately familiar to Ktor developers and allows reuse of Ktor patterns and best practices.

## Conclusion

The DataContext architecture represents a significant improvement that:
- Aligns with Ktor's proven design patterns
- Enables powerful new capabilities (sessions, caching, tracing)
- Maintains backward compatibility where possible
- Provides clear migration path
- Improves code organization and testability

The separation of subject (what) from context (how) is a fundamental architectural principle that makes the pipeline more flexible, maintainable, and production-ready.