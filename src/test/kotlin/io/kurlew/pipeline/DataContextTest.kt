package io.kurlew.pipeline

import io.kurlew.examples.MockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DataContextTest {

    @Test
    fun `context provides correlation ID`() {
        val context = DataContext(
            correlationId = "test-123",
            coroutineContext = Dispatchers.Default
        )

        assertEquals("test-123", context.correlationId)
    }

    @Test
    fun `context can store and retrieve attributes`() {
        val context = DataContext(coroutineContext = Dispatchers.Default)

        context.setAttribute("key1", "value1")
        context.setAttribute("key2", 42)

        assertEquals("value1", context.getAttribute<String>("key1"))
        assertEquals(42, context.getAttribute<Int>("key2"))
        assertNull(context.getAttribute<String>("nonexistent"))
    }

    @Test
    fun `context cache can store and retrieve values`() {
        val context = DataContext(coroutineContext = Dispatchers.Default)

        context.cache.put("user:123", "Alice")

        assertEquals("Alice", context.cache.get("user:123"))
        assertNull(context.cache.get("user:999"))
    }

    @Test
    fun `context cache respects TTL`() = runBlocking {
        val context = DataContext(coroutineContext = Dispatchers.Default)

        context.cache.put("short-lived", "value", ttlMs = 100)

        // Should exist immediately
        assertEquals("value", context.cache.get("short-lived"))

        // Wait for expiration
        kotlinx.coroutines.delay(150)

        // Should be expired
        assertNull(context.cache.get("short-lived"))
    }

    @Test
    fun `context service registry works`() {
        val context = DataContext(coroutineContext = Dispatchers.Default)
        val database = MockDatabase()

        context.services.register("database", database)

        assertTrue(context.services.has("database"))
        assertSame(database, context.services.get("database"))
    }

    @Test
    fun `session context tracks activity`() = runBlocking {
        val session = SessionContext("session-123")
        val initialTime = session.lastActivityAt

        kotlinx.coroutines.delay(10)
        session.touch()

        assertTrue(session.lastActivityAt > initialTime)
    }

    @Test
    fun `session context stores data`() {
        val session = SessionContext("session-123")

        session.set("userId", 42)
        session.set("userName", "Alice")

        assertEquals(42, session.get<Int>("userId"))
        assertEquals("Alice", session.get<String>("userName"))
    }
}