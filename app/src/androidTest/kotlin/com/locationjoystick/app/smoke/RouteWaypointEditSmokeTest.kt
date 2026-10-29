package com.locationjoystick.app.smoke

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import com.locationjoystick.core.data.RouteRepository
import com.locationjoystick.core.model.LatLng
import com.locationjoystick.core.model.Route
import com.locationjoystick.core.model.RouteType
import com.locationjoystick.core.model.Waypoint
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import javax.inject.Inject

/** Exercises the real map touch boundary and the Room-backed saved route together. */
@HiltAndroidTest
class RouteWaypointEditSmokeTest : BaseSmokeTest() {
    @Inject lateinit var routeRepository: RouteRepository

    private lateinit var originalRoute: Route

    @Before
    override fun setup() {
        super.setup()
        composeRule.waitForIdleScreen()
    }

    @Test
    fun map_save_changes_only_the_selected_waypoint_and_survives_reentry() {
        openRoute()
        openWaypoint(1)
        val originalCoordinates = selectedCoordinates()
        assertTrue(originalCoordinates.contains(String.format(Locale.US, "%.6f", originalRoute.waypoints[0].position.latitude)))

        moveDraftOnMap()
        val draftCoordinates = selectedCoordinates()
        assertNotEquals(originalCoordinates, draftCoordinates)
        assertEquals("A map tap must not save before confirmation", originalRoute, savedRoute())

        composeRule.onNodeWithText("Save").performClick()
        awaitDetail()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            savedRoute().waypoints[0].position != originalRoute.waypoints[0].position
        }
        val saved = savedRoute()
        val moved = saved.waypoints[0]
        assertNotEquals(originalRoute.waypoints[0].position, moved.position)
        assertEquals(originalRoute.waypoints[0], moved.copy(position = originalRoute.waypoints[0].position))
        assertEquals(originalRoute.waypoints.drop(1), saved.waypoints.drop(1))
        assertEquals(originalRoute, saved.copy(waypoints = originalRoute.waypoints, updatedAt = originalRoute.updatedAt))
        assertTrue(saved.updatedAt > originalRoute.updatedAt)

        openWaypoint(1)
        assertEquals("Reopening must use the saved coordinate", draftCoordinates, selectedCoordinates())
        composeRule.onNodeWithText("Cancel").performClick()
        awaitDetail()
        assertEquals(saved, savedRoute())
    }

    @Test
    fun cancel_and_back_discard_map_drafts_and_preserve_the_route_name_draft() {
        openRoute()
        composeRule.onNodeWithText("Route name").performTextReplacement("Unsaved route name")
        openWaypoint(1)
        moveDraftOnMap()
        composeRule.onNodeWithText("Cancel").performClick()
        awaitDetail()
        composeRule.onNodeWithText("Route name").assertTextContains("Unsaved route name")
        assertEquals(originalRoute, savedRoute())

        openWaypoint(2)
        moveDraftOnMap()
        Espresso.pressBack()
        awaitDetail()
        composeRule.onNodeWithText("Route name").assertTextContains("Unsaved route name")
        assertEquals("System Back must not persist a map draft", originalRoute, savedRoute())
    }

    @Test
    fun teleport_waypoints_offer_location_editing_separately_from_wait_duration() {
        openRoute(RouteType.TELEPORT)
        composeRule.onAllNodesWithContentDescription("Edit wait duration")[0].performScrollTo().assertIsDisplayed()
        openWaypoint(2)
        assertTrue(selectedCoordinates().contains(String.format(Locale.US, "%.6f", originalRoute.waypoints[1].position.latitude)))
        moveDraftOnMap()
        composeRule.onNodeWithText("Save").performClick()
        awaitDetail()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            savedRoute().waypoints[1].position != originalRoute.waypoints[1].position
        }
        assertEquals(originalRoute.waypoints.map { it.waitSeconds }, savedRoute().waypoints.map { it.waitSeconds })
        composeRule.onAllNodesWithContentDescription("Edit wait duration")[0].performScrollTo().assertIsDisplayed()
    }

    @Test
    fun saving_a_deleted_waypoint_keeps_the_picker_open_and_does_not_reinsert_it() {
        openRoute()
        openWaypoint(1)
        moveDraftOnMap()
        val draftCoordinates = selectedCoordinates()
        runBlocking { routeRepository.removeWaypoint(originalRoute.waypoints[0].id).getOrThrow() }

        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule
                .onAllNodesWithText("Couldn't save this waypoint. Try again.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(MAP_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertEquals("A failed save must retain the draft", draftCoordinates, selectedCoordinates())
        assertEquals(originalRoute.copy(waypoints = originalRoute.waypoints.drop(1)), savedRoute())

        composeRule.onNodeWithText("Cancel").performClick()
        awaitDetail()
        assertEquals(originalRoute.waypoints.drop(1), savedRoute().waypoints)
    }

    private fun openRoute(type: RouteType = RouteType.STRAIGHT) {
        originalRoute =
            Route(
                id = ROUTE_ID,
                name = ROUTE_NAME,
                waypoints =
                    listOf(
                        Waypoint("map-edit-a", LatLng(48.8566, 2.3522), 0, waitSeconds = 11),
                        Waypoint("map-edit-b", LatLng(48.8600, 2.3600), 1, waitSeconds = 22),
                        Waypoint("map-edit-c", LatLng(48.8620, 2.3650), 2, waitSeconds = 33),
                    ),
                isLooping = true,
                routeType = type,
                speedProfileId = "walk",
                randomizeTeleportOrder = type == RouteType.TELEPORT,
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_001L,
            )
        runBlocking { routeRepository.insertRoute(originalRoute).getOrThrow() }
        composeRule.navigateFromIdle("Routes")
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText(ROUTE_NAME, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(ROUTE_NAME, substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithText("Route name").performScrollTo().assertIsDisplayed()
    }

    private fun openWaypoint(number: Int) {
        composeRule.onNodeWithContentDescription("Edit waypoint $number").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            composeRule
                .onAllNodes(
                    hasTestTag(MAP_TAG) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Map ready"),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(COORDINATES_TAG).assertIsDisplayed()
    }

    private fun moveDraftOnMap() {
        val before = selectedCoordinates()
        composeRule.onNodeWithTag(MAP_TAG, useUnmergedTree = true).performTouchInput {
            click(Offset(width * 0.65f, height * 0.35f))
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) { selectedCoordinates() != before }
    }

    private fun selectedCoordinates(): String =
        composeRule
            .onNodeWithTag(COORDINATES_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString(separator = "") { it.text }

    private fun awaitDetail() {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag(MAP_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("Route name").performScrollTo().assertIsDisplayed()
    }

    private fun savedRoute(): Route = runBlocking { requireNotNull(routeRepository.getRouteWithWaypoints(ROUTE_ID).first()) }

    private companion object {
        const val ROUTE_ID = "smoke-waypoint-map-edit"
        const val ROUTE_NAME = "Waypoint Map Edit Route"
        const val MAP_TAG = "waypoint_location_map"
        const val COORDINATES_TAG = "waypoint_selected_coordinates"
    }
}
