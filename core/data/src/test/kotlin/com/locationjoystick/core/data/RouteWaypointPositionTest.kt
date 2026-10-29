package com.locationjoystick.core.data

import app.cash.turbine.test
import com.locationjoystick.core.database.dao.RouteDao
import com.locationjoystick.core.model.LatLng
import com.locationjoystick.core.model.Route
import com.locationjoystick.core.model.RouteType
import com.locationjoystick.core.model.Waypoint
import com.locationjoystick.core.testing.FakeRouteDao
import com.locationjoystick.core.testing.FakeWaypointDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteWaypointPositionTest {
    @Test
    fun `moving one point preserves point identity order waits and route settings`() =
        runTest {
            val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
            val original = route("route-a")
            val otherRoute = route("route-b")
            fixture.repository.insertRoute(original).getOrThrow()
            fixture.repository.insertRoute(otherRoute).getOrThrow()
            val replacement = LatLng(37.58, 127.01)

            fixture.repository.getRouteWithWaypoints(original.id).test {
                assertEquals(original, awaitItem())
                fixture.repository.updateWaypointPosition(original.id, "route-a-point-1", replacement).getOrThrow()
                val updated = requireNotNull(awaitItem())

                assertTrue(updated.updatedAt > original.updatedAt)
                assertEquals(
                    original.copy(
                        updatedAt = updated.updatedAt,
                        waypoints =
                            original.waypoints.map { point ->
                                if (point.id == "route-a-point-1") point.copy(position = replacement) else point
                            },
                    ),
                    updated,
                )
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(otherRoute, fixture.repository.getRouteWithWaypoints(otherRoute.id).first())
        }

    @Test
    fun `a point belonging to another route is not moved`() =
        runTest {
            val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
            val first = route("route-a")
            val second = route("route-b")
            fixture.repository.insertRoute(first).getOrThrow()
            fixture.repository.insertRoute(second).getOrThrow()

            val result = fixture.repository.updateWaypointPosition(first.id, "route-b-point-1", LatLng(1.0, 2.0))

            assertTrue(result.isFailure)
            assertEquals(first, fixture.repository.getRouteWithWaypoints(first.id).first())
            assertEquals(second, fixture.repository.getRouteWithWaypoints(second.id).first())
        }

    @Test
    fun `a missing point fails without touching the route timestamp`() =
        runTest {
            val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
            val original = route("route-a")
            fixture.repository.insertRoute(original).getOrThrow()

            assertTrue(fixture.repository.updateWaypointPosition(original.id, "missing", LatLng(1.0, 2.0)).isFailure)
            assertEquals(original, fixture.repository.getRouteWithWaypoints(original.id).first())
        }

    @Test
    fun `a point deleted while map editing is open is not recreated`() =
        runTest {
            val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
            val original = route("route-a")
            fixture.repository.insertRoute(original).getOrThrow()
            fixture.repository.removeWaypoint("route-a-point-1").getOrThrow()

            assertTrue(
                fixture.repository.updateWaypointPosition(original.id, "route-a-point-1", LatLng(1.0, 2.0)).isFailure,
            )
            val remaining = requireNotNull(fixture.repository.getRouteWithWaypoints(original.id).first())
            assertEquals(original.copy(waypoints = original.waypoints.filterNot { it.id == "route-a-point-1" }), remaining)
        }

    @Test
    fun `a deleted route cannot receive a point update`() =
        runTest {
            val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
            val original = route("route-a")
            fixture.repository.insertRoute(original).getOrThrow()
            fixture.repository.deleteRoute(original.id).getOrThrow()
            val pointsBefore = fixture.waypointDao.snapshot()

            assertTrue(
                fixture.repository.updateWaypointPosition(original.id, "route-a-point-1", LatLng(1.0, 2.0)).isFailure,
            )
            assertEquals(pointsBefore, fixture.waypointDao.snapshot())
            assertEquals(null, fixture.repository.getRouteWithWaypoints(original.id).first())
        }

    @Test
    fun `invalid coordinates are rejected without mutating storage`() =
        runTest {
            val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
            val original = route("route-a")
            fixture.repository.insertRoute(original).getOrThrow()
            val invalid =
                listOf(
                    LatLng(Double.NaN, 0.0),
                    LatLng(Double.POSITIVE_INFINITY, 0.0),
                    LatLng(Double.NEGATIVE_INFINITY, 0.0),
                    LatLng(90.1, 0.0),
                    LatLng(-90.1, 0.0),
                    LatLng(0.0, Double.NaN),
                    LatLng(0.0, Double.POSITIVE_INFINITY),
                    LatLng(0.0, Double.NEGATIVE_INFINITY),
                    LatLng(0.0, 180.1),
                    LatLng(0.0, -180.1),
                )

            invalid.forEach { point ->
                assertTrue(fixture.repository.updateWaypointPosition(original.id, "route-a-point-1", point).isFailure)
                assertEquals(original, fixture.repository.getRouteWithWaypoints(original.id).first())
            }
        }

    @Test
    fun `coordinate boundaries are accepted`() =
        runTest {
            val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
            fixture.repository.insertRoute(route("route-a")).getOrThrow()

            fixture.repository.updateWaypointPosition("route-a", "route-a-point-1", LatLng(-90.0, 180.0)).getOrThrow()

            val saved = requireNotNull(fixture.repository.getRouteWithWaypoints("route-a").first())
            assertEquals(LatLng(-90.0, 180.0), saved.waypoints[1].position)
        }

    @Test
    fun `storage errors are returned as failure`() =
        runTest {
            val dao = mockk<RouteDao>()
            coEvery { dao.updateWaypointPosition(any(), any(), any(), any(), any()) } throws
                IllegalStateException("write failed")
            val repository = RouteRepository(dao, UnconfinedTestDispatcher(testScheduler))

            val result = repository.updateWaypointPosition("route-a", "point-1", LatLng(1.0, 2.0))

            assertFalse(result.isSuccess)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals("write failed", result.exceptionOrNull()?.message)
            coVerify(exactly = 1) { dao.updateWaypointPosition("route-a", "point-1", 1.0, 2.0, any()) }
        }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
    ) {
        val waypointDao = FakeWaypointDao()
        val repository = RouteRepository(FakeRouteDao(waypointDao), dispatcher)
    }

    private fun route(id: String) =
        Route(
            id = id,
            name = "Saved route $id",
            waypoints =
                listOf(
                    Waypoint("$id-point-0", LatLng(37.56, 126.97), 0, waitSeconds = 7),
                    Waypoint("$id-point-1", LatLng(37.57, 126.98), 1, waitSeconds = 11),
                    Waypoint("$id-point-2", LatLng(37.59, 126.99), 2, waitSeconds = 13),
                ),
            isLooping = true,
            routeType = RouteType.TELEPORT,
            speedProfileId = "bike",
            randomizeTeleportOrder = true,
            createdAt = 100L,
            updatedAt = 200L,
        )
}
