package com.locationjoystick.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.locationjoystick.core.model.SavedItemSortMode
import com.locationjoystick.core.testing.FakePreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomRouteOrderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `first custom selection keeps visible order and later selections restore it`() =
        runTest {
            val source = AppPreferencesDataSource(FakePreferencesDataStore())
            source.enableCustomRouteOrder(listOf("c", "a", "b"))
            assertEquals(SavedItemSortMode.CUSTOM, source.getRoutesSortMode().first())
            assertEquals(listOf("c", "a", "b"), source.getCustomRouteOrder().first())

            source.setCustomRouteOrder(listOf("b", "c", "a"))
            source.setRoutesSortMode(SavedItemSortMode.NAME_ASCENDING)
            source.enableCustomRouteOrder(listOf("a", "b", "c"))

            assertEquals(listOf("b", "c", "a"), source.getCustomRouteOrder().first())
            assertEquals(SavedItemSortMode.CUSTOM, source.getRoutesSortMode().first())
            assertEquals(SavedItemSortMode.NEWEST_FIRST, source.getFavoritesSortMode().first())
        }

    @Test
    fun `order write normalizes duplicate and empty identities without changing automatic mode`() =
        runTest {
            val source = AppPreferencesDataSource(FakePreferencesDataStore())
            source.setRoutesSortMode(SavedItemSortMode.OLDEST_FIRST)
            source.setCustomRouteOrder(listOf("a", "", "b", "a", " "))
            assertEquals(listOf("a", "b"), source.getCustomRouteOrder().first())
            assertEquals(SavedItemSortMode.OLDEST_FIRST, source.getRoutesSortMode().first())
        }

    @Test
    fun `order and custom mode survive reopening the real preferences file`() =
        runTest {
            val file = File(temporaryFolder.root, "route-order.preferences_pb")
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }))
                source.enableCustomRouteOrder(listOf("b", "a"))
                source.setCustomRouteOrder(listOf("a", "b"))
            } finally {
                firstScope.coroutineContext[Job]!!.cancelAndJoin()
            }
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { file }))
                assertEquals(listOf("a", "b"), source.getCustomRouteOrder().first())
                assertEquals(SavedItemSortMode.CUSTOM, source.getRoutesSortMode().first())
            } finally {
                secondScope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }

    @Test
    fun `snapshot round trip retains order and reset clears it`() =
        runTest {
            val source = AppPreferencesDataSource(FakePreferencesDataStore())
            source.enableCustomRouteOrder(listOf("b", "a"))
            val snapshot = source.getSettingsSnapshot().first()
            val restored = AppPreferencesDataSource(FakePreferencesDataStore())
            restored.applySnapshot(snapshot)
            assertEquals(listOf("b", "a"), restored.getCustomRouteOrder().first())
            restored.setOnboardingComplete(true)
            restored.clearAllExceptOnboarding()
            assertTrue(restored.getCustomRouteOrder().first().isEmpty())
            assertEquals(SavedItemSortMode.NEWEST_FIRST, restored.getRoutesSortMode().first())
            assertTrue(restored.getOnboardingComplete().first())
        }

    @Test
    fun `invalid stored order falls back safely`() =
        runTest {
            val store = FakePreferencesDataStore()
            val source = AppPreferencesDataSource(store)
            for (raw in listOf("not-json", "[1]", "{}")) {
                store.writeRaw("custom_route_order", raw)
                assertTrue(source.getCustomRouteOrder().first().isEmpty())
            }
        }

    @Test
    fun `favorites reject custom order and recover an invalid stored custom mode`() =
        runTest {
            val store = FakePreferencesDataStore()
            val source = AppPreferencesDataSource(store)
            assertFalse(runCatching { source.setFavoritesSortMode(SavedItemSortMode.CUSTOM) }.isSuccess)
            store.writeRaw("favorites_sort_mode", SavedItemSortMode.CUSTOM.name)
            assertEquals(SavedItemSortMode.NEWEST_FIRST, source.getFavoritesSortMode().first())
        }
}
