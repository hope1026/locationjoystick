package com.locationjoystick.core.location

import android.location.Location
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

internal interface FusedLocationPort {
    suspend fun setMockMode(enabled: Boolean)

    suspend fun setMockLocation(location: Location)
}

internal class FusedSessionSupersededException : CancellationException("Fused mock start was superseded")

/**
 * Serializes the Fused mock session with location writes and cleanup.
 *
 * A caller captures the token returned by [start] with each location. [invalidate] immediately
 * rejects those queued writes, even before the caller can launch suspending cleanup. Passing its
 * return value to [stop] prevents delayed cleanup from disabling a newer session.
 */
internal class FusedMockLocationSink(
    private val port: FusedLocationPort,
    private val operationTimeoutMillis: Long = DEFAULT_OPERATION_TIMEOUT_MILLIS,
    private val onCleanupFailure: (Throwable) -> Unit = { error ->
        Log.e(TAG, "Failed to clear Fused mock mode", error)
    },
) {
    private val mutex = Mutex()
    private val generation = AtomicLong()

    @Volatile private var activeToken: Long? = null

    // Set before enabling: a failed or cancelled Task may already have reached Play services.
    // Keep this true after failed cleanup so the next stop can retry.
    private var mockModeMayBeEnabled = false
    private var lastLocationElapsedNanos: Long? = null

    init {
        require(operationTimeoutMillis > 0L) { "Operation timeout must be positive" }
    }

    suspend fun start(): Long {
        val token = generation.incrementAndGet()
        try {
            return mutex.withLock {
                requireCurrent(token)
                mockModeMayBeEnabled = true
                withTimeout(operationTimeoutMillis) { port.setMockMode(true) }
                if (generation.get() != token) {
                    clearMockModeLocked()
                    throw FusedSessionSupersededException()
                }
                activeToken = token
                lastLocationElapsedNanos = null
                token
            }
        } catch (error: Throwable) {
            // Task cancellation cannot retract a remote Binder request. Always attempt false
            // before releasing this failed session; never leave a cancelled enable unattended.
            withContext(NonCancellable) {
                mutex.withLock {
                    if (generation.get() == token || activeToken == token) {
                        activeToken = null
                        try {
                            clearMockModeLocked()
                        } catch (cleanupError: Throwable) {
                            error.addSuppressed(cleanupError)
                            onCleanupFailure(cleanupError)
                        }
                    }
                }
            }
            throw error
        }
    }

    /** Returns false for an inactive or superseded session; failed writes propagate to the caller. */
    suspend fun push(
        sessionToken: Long,
        location: Location,
    ): Boolean =
        mutex.withLock {
            if (generation.get() != sessionToken || activeToken != sessionToken) return@withLock false
            val timestamp = location.elapsedRealtimeNanos
            if (lastLocationElapsedNanos?.let { timestamp <= it } == true) return@withLock false
            withTimeout(operationTimeoutMillis) { port.setMockLocation(location) }
            lastLocationElapsedNanos = timestamp
            true
        }

    /** Invalidate synchronously on stop, park, error, or service destruction. */
    fun invalidate(): Long {
        val token = generation.incrementAndGet()
        activeToken = null
        return token
    }

    suspend fun stop(invalidationToken: Long = invalidate()) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (generation.get() != invalidationToken) return@withLock
                activeToken = null
                clearMockModeLocked()
            }
        }
    }

    private fun requireCurrent(token: Long) {
        if (generation.get() != token) throw FusedSessionSupersededException()
    }

    private suspend fun clearMockModeLocked() {
        if (!mockModeMayBeEnabled) return
        withContext(NonCancellable) {
            withTimeout(operationTimeoutMillis) { port.setMockMode(false) }
        }
        mockModeMayBeEnabled = false
    }

    private companion object {
        const val TAG = "FusedMockLocationSink"
        const val DEFAULT_OPERATION_TIMEOUT_MILLIS = 5_000L
    }
}
