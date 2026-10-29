package com.locationjoystick.core.location

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/**
 * Retries preparation invalidated by a newer active session, even when a StateFlow collector
 * observed RUNNING both before and after a short IDLE transition. A terminal preparation
 * failure must make [isActive] false; cancellation of the owning coroutine always propagates.
 */
internal suspend fun prepareActiveProviders(
    isActive: () -> Boolean,
    prepare: suspend () -> Boolean,
): Boolean {
    currentCoroutineContext().ensureActive()
    while (isActive()) {
        if (prepare()) {
            currentCoroutineContext().ensureActive()
            return isActive()
        }
        // A superseded attempt can finish synchronously. Give stop/cancellation work a turn
        // before retrying rather than spinning in the observer's dispatcher thread.
        yield()
    }
    currentCoroutineContext().ensureActive()
    return false
}
