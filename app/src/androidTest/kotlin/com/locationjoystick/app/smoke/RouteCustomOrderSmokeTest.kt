package com.locationjoystick.app.smoke

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import com.locationjoystick.core.data.RouteRepository
import com.locationjoystick.core.data.SettingsRepository
import com.locationjoystick.core.model.LatLng
import com.locationjoystick.core.model.Route
import com.locationjoystick.core.model.RouteType
import com.locationjoystick.core.model.SavedItemSortMode
import com.locationjoystick.core.model.Waypoint
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import javax.inject.Inject

/** Exercises pointer reordering and real preference persistence without starting GPS simulation. */
@HiltAndroidTest
class RouteCustomOrderSmokeTest : BaseSmokeTest() {
    @Inject lateinit var routeRepository: RouteRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    private val original = NAMES.mapIndexed { index, name -> route(name, index) }
    private val initialIds = original.map { it.id }

    @Before
    override fun setup() {
        super.setup()
        runBlocking {
            routeRepository.deleteAllRoutes().getOrThrow()
            original.forEach { routeRepository.insertRoute(it).getOrThrow() }
            settingsRepository.setCustomRouteOrder(emptyList())
            settingsRepository.setRoutesSortMode(SavedItemSortMode.NAME_ASCENDING)
        }
        composeRule.waitForIdleScreen()
        composeRule.navigateFromIdle("Routes")
        awaitVisibleOrder(initialIds)
    }

    @Test
    fun dragging_down_and_up_persists_and_other_sort_modes_preserve_custom_order() {
        selectSort("Custom order")
        awaitPersistedOrder(initialIds)

        drag(initialIds[0], initialIds[2], downward = true)
        val afterDown = listOf(initialIds[1], initialIds[2], initialIds[0], initialIds[3], initialIds[4])
        awaitPersistedOrder(afterDown)
        awaitVisibleOrder(afterDown)

        drag(initialIds[4], initialIds[1], downward = false)
        val afterUp = listOf(initialIds[4], initialIds[1], initialIds[2], initialIds[0], initialIds[3])
        awaitPersistedOrder(afterUp)
        awaitVisibleOrder(afterUp)
        assertRouteDataUnchanged()

        selectSort("Z–A")
        awaitVisibleOrder(initialIds.reversed())
        assertEquals(afterUp, persistedOrder())
        selectSort("Custom order")
        awaitVisibleOrder(afterUp)

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdleScreen()
        composeRule.navigateViaDrawer("Routes")
        awaitVisibleOrder(afterUp)
        assertEquals(SavedItemSortMode.CUSTOM, runBlocking { settingsRepository.getRoutesSortMode().first() })
        assertEquals(afterUp, persistedOrder())
        assertRouteDataUnchanged()
    }

    @Test
    fun searching_disables_dragging_without_changing_hidden_routes_order() {
        selectSort("Custom order")
        awaitPersistedOrder(initialIds)
        composeRule.onNodeWithText("Search routes").performTextReplacement("Alpha")
        composeRule.onNodeWithText("Clear search to reorder routes.").assertIsDisplayed()
        val handle = composeRule.onNodeWithTag(HANDLE_PREFIX + initialIds[0])
        handle.assertIsNotEnabled()
        handle.performTouchInput {
            down(center)
            advanceEventTime(650L)
            moveTo(Offset(center.x, center.y + 100f), delayMillis = 300L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(initialIds, persistedOrder())
        assertRouteDataUnchanged()

        composeRule.onNodeWithText("Search routes").performTextClearance()
        Espresso.closeSoftKeyboard()
        awaitVisibleOrder(initialIds)
        composeRule.onNodeWithTag(HANDLE_PREFIX + initialIds[0]).assertIsEnabled()
    }

    @Test
    fun new_and_deleted_routes_do_not_displace_existing_custom_ids() {
        selectSort("Custom order")
        awaitPersistedOrder(initialIds)
        drag(initialIds[0], initialIds[2], downward = true)
        val custom = listOf(initialIds[1], initialIds[2], initialIds[0], initialIds[3], initialIds[4])
        awaitPersistedOrder(custom)

        val newRoute = route("Foxtrot", 5)
        runBlocking {
            routeRepository.insertRoute(newRoute).getOrThrow()
            routeRepository.deleteRoute(initialIds[1]).getOrThrow()
        }
        val expected = custom.filterNot { it == initialIds[1] } + newRoute.id
        awaitVisibleOrder(expected)
        assertEquals(
            (original.filterNot { it.id == initialIds[1] } + newRoute).sortedBy { it.id },
            savedRoutes().sortedBy { it.id },
        )
    }

    @Test
    fun favorites_sort_menu_does_not_offer_route_custom_order() {
        composeRule.navigateViaDrawer("Favorites")
        composeRule.onNodeWithContentDescription("Sort").performClick()
        composeRule.onAllNodesWithText("Custom order").assertCountEquals(0)
        composeRule.onNodeWithText("A–Z").assertIsDisplayed()
    }

    private fun selectSort(label: String) {
        composeRule.onNodeWithContentDescription("Sort").performClick()
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
    }

    private fun awaitPersistedOrder(expected: List<String>) {
        composeRule.waitUntil(timeoutMillis = 10_000L) { persistedOrder() == expected }
        composeRule.waitForIdle()
    }

    private fun persistedOrder(): List<String> = runBlocking { settingsRepository.getCustomRouteOrder().first() }

    private fun awaitVisibleOrder(expected: List<String>) {
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            val positioned =
                expected.mapNotNull { id ->
                    composeRule
                        .onAllNodesWithTag(CARD_PREFIX + id)
                        .fetchSemanticsNodes()
                        .firstOrNull()
                        ?.let { id to it.boundsInRoot.top }
                }
            positioned.size == expected.size && positioned.sortedBy { it.second }.map { it.first } == expected
        }
        composeRule.waitForIdle()
    }

    private fun drag(
        sourceId: String,
        targetId: String,
        downward: Boolean,
    ) {
        composeRule.onNodeWithTag(HANDLE_PREFIX + sourceId).performScrollTo().assertIsDisplayed()
        val list = composeRule.onNodeWithTag(LIST_TAG).fetchSemanticsNode().boundsInRoot
        val handle = composeRule.onNodeWithTag(HANDLE_PREFIX + sourceId).fetchSemanticsNode().boundsInRoot
        val target = composeRule.onNodeWithTag(CARD_PREFIX + targetId).fetchSemanticsNode().boundsInRoot
        val start = handle.center - list.topLeft
        val targetY = target.center.y + target.height * (if (downward) 0.2f else -0.2f)
        val end = Offset(start.x, (targetY - list.top).coerceIn(4f, list.height - 4f))
        composeRule.onNodeWithTag(LIST_TAG).performTouchInput {
            down(start)
            advanceEventTime(650L)
            repeat(20) { step ->
                val fraction = (step + 1) / 20f
                moveTo(Offset(start.x, start.y + (end.y - start.y) * fraction), delayMillis = 40L)
            }
            up()
        }
        composeRule.waitForIdle()
    }

    private fun assertRouteDataUnchanged() {
        assertEquals(original.sortedBy { it.id }, savedRoutes().sortedBy { it.id })
    }

    private fun savedRoutes(): List<Route> = runBlocking { routeRepository.getRoutes().first() }

    private fun route(
        name: String,
        index: Int,
    ): Route =
        Route(
            id = "custom-order-${name.lowercase()}",
            name = name,
            waypoints =
                listOf(
                    Waypoint("$name-first", LatLng(48.8566 + index * 0.001, 2.3522), 0, 11),
                    Waypoint("$name-second", LatLng(48.8600 + index * 0.001, 2.3600), 1, 22),
                ),
            isLooping = true,
            routeType = RouteType.STRAIGHT,
            speedProfileId = "walk",
            createdAt = 1_700_000_000_000L + index,
            updatedAt = 1_700_000_000_100L + index,
        )

    private companion object {
        val NAMES = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo")
        const val HANDLE_PREFIX = "route_drag_"
        const val CARD_PREFIX = "saved_route_"
        const val LIST_TAG = "saved_routes_list"
    }
}
