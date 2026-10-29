package com.locationjoystick.app.smoke

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationServices
import com.locationjoystick.core.common.util.haversineDistance
import com.locationjoystick.core.data.LocationRepository
import com.locationjoystick.core.data.SettingsRepository
import com.locationjoystick.core.data.WalkCoordinator
import com.locationjoystick.core.location.MapController
import com.locationjoystick.core.location.MockLocationIntentBuilder
import com.locationjoystick.core.location.WalkMode
import com.locationjoystick.core.model.LatLng
import com.locationjoystick.core.model.MockLocationState
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.hypot

/** Uses the actual routing server and actual mock-location APIs on a dedicated emulator. */
@HiltAndroidTest
class RoadWalkSmokeTest : BaseSmokeTest() {
    @Inject lateinit var controller: MapController

    @Inject lateinit var locationRepository: LocationRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var walkCoordinator: WalkCoordinator

    @Test
    fun road_walk_resolves_a_real_route_and_updates_gps_and_fused() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val result = JSONObject().put("status", "running")
            val file = File(context.filesDir, "road-walk-evidence.json")
            val start = LatLng(37.5665, 126.9780)
            val destination = LatLng(37.5700, 126.9849)
            try {
                settingsRepository.setJitterIdleRadius(0.0)
                settingsRepository.setJitterMovingRadius(0.0)
                settingsRepository.setWalkSpeed(2.0)
                settingsRepository.setActiveProfileId("walk")
                locationRepository.setPositionInternal(start)
                ContextCompat.startForegroundService(
                    context,
                    MockLocationIntentBuilder.startSpoofing(context, start.latitude, start.longitude),
                )
                withTimeout(15_000L) {
                    while (locationRepository.mockLocationState.value != MockLocationState.RUNNING) delay(100L)
                }
                controller.walkViaRoads(destination)
                withTimeout(20_000L) {
                    while (locationRepository.routeWaypoints.value.isNullOrEmpty()) delay(100L)
                }
                val road = requireNotNull(locationRepository.routeWaypoints.value)
                assertTrue("Must resolve a road polyline, not a two-point fallback", road.size > 2)
                assertTrue((controller.sharedState.value.walkMode as? WalkMode.Walking)?.isViaRoads == true)
                val first = requireNotNull(locationRepository.currentPosition.value)
                val geometry = listOf(start) + road
                val samples = mutableListOf<LatLng>()
                repeat(25) {
                    delay(1_000L)
                    samples += requireNotNull(locationRepository.currentPosition.value)
                }
                val moved = haversineDistance(first, samples.last())
                val maxDeviation =
                    samples.maxOf { point -> geometry.zipWithNext { from, to -> segmentDistance(point, from, to) }.min() }
                assertTrue("Road walk must advance", moved > 10.0)
                assertTrue("Walk samples must follow the route polyline", maxDeviation < 5.0)
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    throw AssertionError("Fine location permission is required")
                }
                val manager = requireNotNull(context.getSystemService(LocationManager::class.java))
                val gps = requireNotNull(manager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
                val client = LocationServices.getFusedLocationProviderClient(context)
                val fused = requireNotNull(withTimeout(5_000L) { client.lastLocation.await() })
                assertTrue(LocationCompat.isMock(gps) && LocationCompat.isMock(fused))
                assertTrue(gps.distanceTo(fused) < 10f)
                result
                    .put("status", "passed")
                    .put("routePoints", road.size)
                    .put("observationSeconds", 25)
                    .put("movementMeters", moved)
                    .put("maxPolylineDeviationMeters", maxDeviation)
                    .put("gpsFusedDistanceMeters", gps.distanceTo(fused))
            } catch (error: Throwable) {
                result.put("status", "failed").put("error", error.toString())
                throw error
            } finally {
                walkCoordinator.cancel()
                context.startService(MockLocationIntentBuilder.stopSpoofing(context))
                file.writeText(result.toString(2))
            }
        }

    private fun segmentDistance(
        p: LatLng,
        a: LatLng,
        b: LatLng,
    ): Double {
        val scale = cos(Math.toRadians(p.latitude))
        val ax = (a.longitude - p.longitude) * scale * 111_320.0
        val ay = (a.latitude - p.latitude) * 111_320.0
        val bx = (b.longitude - p.longitude) * scale * 111_320.0
        val by = (b.latitude - p.latitude) * 111_320.0
        val dx = bx - ax
        val dy = by - ay
        val norm = dx * dx + dy * dy
        val t = if (norm == 0.0) 0.0 else (-(ax * dx + ay * dy) / norm).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }
}
