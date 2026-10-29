package com.locationjoystick.core.location

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FusedMockLocationSinkTest {
    private val fix = mockk<Location> { every { elapsedRealtimeNanos } returns 1L }

    @Test
    fun `enable finishes before the first fix is sent`() =
        runTest {
            val port = FakePort()
            val enabled = CompletableDeferred<Unit>()
            port.onMode = { if (it) enabled.await() }
            val sink = FusedMockLocationSink(port)
            val start = async { sink.start() }
            runCurrent()
            val push = async { sink.push(1L, fix) }
            runCurrent()

            assertFalse(start.isCompleted)
            assertEquals(listOf("mode:true"), port.events)
            enabled.complete(Unit)
            runCurrent()

            assertEquals(1L, start.await())
            assertTrue(push.await())
            assertEquals(listOf("mode:true", "fix"), port.events)
            assertSame(fix, port.fixes.single())
        }

    @Test
    fun `enable failure clears possible remote mode and rejects fixes`() =
        runTest {
            val port = FakePort()
            val failure = IllegalStateException("enable failed")
            port.onMode = { if (it) throw failure }
            val sink = FusedMockLocationSink(port)

            val result = runCatching { sink.start() }

            assertFailure(failure, result.exceptionOrNull())
            assertFalse(sink.push(1L, fix))
            assertEquals(listOf("mode:true", "mode:false"), port.events)
        }

    @Test
    fun `cancelled enable still attempts to clear remote mock mode`() =
        runTest {
            val port = FakePort()
            port.onMode = { if (it) awaitCancellation() }
            val sink = FusedMockLocationSink(port)
            val start = launch { sink.start() }
            runCurrent()

            start.cancel()
            runCurrent()

            assertTrue(start.isCancelled)
            assertEquals(listOf("mode:true", "mode:false"), port.events)
            assertFalse(sink.push(1L, fix))
        }

    @Test
    fun `enable timeout propagates and performs bounded cleanup`() =
        runTest {
            val port = FakePort()
            port.onMode = { if (it) awaitCancellation() }
            val sink = FusedMockLocationSink(port, operationTimeoutMillis = 1_000L)
            val result = async { runCatching { sink.start() } }
            runCurrent()
            advanceTimeBy(1_001L)
            runCurrent()

            assertTrue(result.await().exceptionOrNull() is TimeoutCancellationException)
            assertEquals(listOf("mode:true", "mode:false"), port.events)
        }

    @Test
    fun `stop waits for an in-flight write and drops queued fixes immediately`() =
        runTest {
            val port = FakePort()
            val sink = FusedMockLocationSink(port)
            val token = sink.start()
            val write = CompletableDeferred<Unit>()
            port.onFix = { write.await() }
            val first = async { sink.push(token, fix) }
            runCurrent()
            val queued = async { sink.push(token, fix) }
            runCurrent()
            val cleanupToken = sink.invalidate()
            val stop = async { sink.stop(cleanupToken) }
            runCurrent()

            assertFalse(stop.isCompleted)
            assertEquals(listOf("mode:true", "fix"), port.events)
            write.complete(Unit)
            runCurrent()

            assertTrue(first.await())
            assertFalse(queued.await())
            stop.await()
            assertEquals(listOf("mode:true", "fix", "mode:false"), port.events)
            assertFalse(sink.push(token, fix))
        }

    @Test
    fun `delayed cleanup cannot disable a newer session`() =
        runTest {
            val port = FakePort()
            val sink = FusedMockLocationSink(port)
            val previous = sink.start()
            val oldCleanup = sink.invalidate()
            val current = sink.start()

            sink.stop(oldCleanup)

            assertFalse(sink.push(previous, fix))
            assertTrue(sink.push(current, fix))
            assertEquals(listOf("mode:true", "mode:true", "fix"), port.events)
        }

    @Test
    fun `invalidating during enable clears it before a new session starts`() =
        runTest {
            val port = FakePort()
            val enabled = CompletableDeferred<Unit>()
            port.onMode = { if (it) enabled.await() }
            val sink = FusedMockLocationSink(port)
            val previous = async { runCatching { sink.start() } }
            runCurrent()
            val cleanup = sink.invalidate()
            val current = async { sink.start() }
            runCurrent()
            enabled.complete(Unit)
            runCurrent()

            assertTrue(previous.await().exceptionOrNull() is CancellationException)
            val token = current.await()
            sink.stop(cleanup)
            assertTrue(sink.push(token, fix))
            assertEquals(listOf("mode:true", "mode:false", "mode:true", "fix"), port.events)
        }

    @Test
    fun `stop is idempotent and the sink can restart`() =
        runTest {
            val port = FakePort()
            val sink = FusedMockLocationSink(port)
            val previous = sink.start()
            sink.stop()
            sink.stop()
            val current = sink.start()

            assertFalse(sink.push(previous, fix))
            assertTrue(sink.push(current, fix))
            assertEquals(listOf("mode:true", "mode:false", "mode:true", "fix"), port.events)
        }

    @Test
    fun `write failures propagate and cleanup remains possible`() =
        runTest {
            val port = FakePort()
            val sink = FusedMockLocationSink(port)
            val token = sink.start()
            val failure = IllegalStateException("write failed")
            port.onFix = { throw failure }

            assertFailure(failure, runCatching { sink.push(token, fix) }.exceptionOrNull())
            sink.stop()

            assertEquals(listOf("mode:true", "fix", "mode:false"), port.events)
        }

    @Test
    fun `failed cleanup stays retryable and does not accept old writes`() =
        runTest {
            val port = FakePort()
            val sink = FusedMockLocationSink(port)
            val token = sink.start()
            val failure = IllegalStateException("clear failed")
            port.onMode = { if (!it) throw failure }

            assertFailure(failure, runCatching { sink.stop() }.exceptionOrNull())
            assertFalse(sink.push(token, fix))
            port.onMode = {}
            sink.stop()

            assertEquals(listOf("mode:true", "mode:false", "mode:false"), port.events)
        }

    @Test
    fun `enable failure preserves cleanup failure and permits retry`() =
        runTest {
            val port = FakePort()
            val failure = IllegalStateException("enable failed")
            val cleanupFailure = IllegalStateException("clear failed")
            val cleanupErrors = mutableListOf<Throwable>()
            port.onMode = { if (it) throw failure else throw cleanupFailure }
            val sink = FusedMockLocationSink(port, onCleanupFailure = { cleanupErrors += it })

            val actual = runCatching { sink.start() }.exceptionOrNull()
            assertFailure(failure, actual)
            val suppressed =
                generateSequence(actual) { it.cause }
                    .flatMap { it.suppressed.asSequence() }
                    .firstOrNull { it.javaClass == cleanupFailure.javaClass && it.message == cleanupFailure.message }
            assertFailure(cleanupFailure, suppressed)
            assertEquals(1, cleanupErrors.size)
            assertFailure(cleanupFailure, cleanupErrors.single())
            assertFalse(sink.push(1L, fix))
            port.onMode = {}
            sink.stop()

            assertEquals(listOf("mode:true", "mode:false", "mode:false"), port.events)
        }

    @Test
    fun `older queued fixes cannot overwrite a newer fix in the same session`() =
        runTest {
            val port = FakePort()
            val sink = FusedMockLocationSink(port)
            val token = sink.start()
            val newer = mockk<Location> { every { elapsedRealtimeNanos } returns 2L }

            assertTrue(sink.push(token, newer))
            assertFalse(sink.push(token, fix))
            assertFalse(sink.push(token, newer))

            assertEquals(listOf(newer), port.fixes)
            sink.stop()
            val nextToken = sink.start()
            assertTrue(sink.push(nextToken, fix))
        }

    // Coroutine stacktrace recovery may copy an exception across suspension boundaries.
    // The caller-visible failure and its cleanup evidence matter, not object identity.
    private fun assertFailure(
        expected: Throwable,
        actual: Throwable?,
    ) {
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
    }

    private class FakePort : FusedLocationPort {
        val events = mutableListOf<String>()
        val fixes = mutableListOf<Location>()
        var onMode: suspend (Boolean) -> Unit = {}
        var onFix: suspend (Location) -> Unit = {}

        override suspend fun setMockMode(enabled: Boolean) {
            events += "mode:$enabled"
            onMode(enabled)
        }

        override suspend fun setMockLocation(location: Location) {
            events += "fix"
            fixes += location
            onFix(location)
        }
    }
}
