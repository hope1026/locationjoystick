package com.locationjoystick.core.location

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MockProviderPreparationTest {
    @Test
    fun `superseded attempt retries when idle then running was coalesced`() =
        runTest {
            var active = true
            var attempts = 0
            val firstEnable = CompletableDeferred<Unit>()
            val result =
                async {
                    prepareActiveProviders(
                        isActive = { active },
                        prepare = {
                            attempts++
                            if (attempts == 1) {
                                firstEnable.await()
                                false
                            } else {
                                true
                            }
                        },
                    )
                }
            runCurrent()

            active = false
            active = true
            firstEnable.complete(Unit)
            runCurrent()

            assertTrue(result.await())
            assertEquals(2, attempts)
        }

    @Test
    fun `stop during pending enable exits without retrying`() =
        runTest {
            var active = true
            var attempts = 0
            val enable = CompletableDeferred<Unit>()
            val result =
                async {
                    prepareActiveProviders(
                        isActive = { active },
                        prepare = {
                            attempts++
                            enable.await()
                            false
                        },
                    )
                }
            runCurrent()

            active = false
            enable.complete(Unit)
            runCurrent()

            assertFalse(result.await())
            assertEquals(1, attempts)
        }

    @Test
    fun `stop during successful preparation does not report active providers`() =
        runTest {
            var active = true
            val enabled = CompletableDeferred<Unit>()
            val result =
                async {
                    prepareActiveProviders(
                        isActive = { active },
                        prepare = {
                            enabled.await()
                            true
                        },
                    )
                }
            runCurrent()

            active = false
            enabled.complete(Unit)
            runCurrent()

            assertFalse(result.await())
        }

    @Test
    fun `terminal failure does not busy retry`() =
        runTest {
            var active = true
            var attempts = 0

            val result =
                prepareActiveProviders(
                    isActive = { active },
                    prepare = {
                        attempts++
                        active = false
                        false
                    },
                )

            assertFalse(result)
            assertEquals(1, attempts)
        }

    @Test
    fun `cancelling the owning coroutine propagates and never retries`() =
        runTest {
            var attempts = 0
            var returned = false
            val task =
                launch {
                    prepareActiveProviders(
                        isActive = { true },
                        prepare = {
                            attempts++
                            awaitCancellation()
                        },
                    )
                    returned = true
                }
            runCurrent()

            task.cancel()
            runCurrent()

            assertTrue(task.isCancelled)
            assertFalse(returned)
            assertEquals(1, attempts)
        }

    @Test
    fun `inactive session never begins preparation`() =
        runTest {
            var attempts = 0

            assertFalse(
                prepareActiveProviders(
                    isActive = { false },
                    prepare = {
                        attempts++
                        true
                    },
                ),
            )
            assertEquals(0, attempts)
        }
}
