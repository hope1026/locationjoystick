package com.locationjoystick.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.locationjoystick.app.MainActivity
import com.locationjoystick.core.data.LocationRepository
import com.locationjoystick.core.data.RouteRepository
import com.locationjoystick.core.data.SettingsRepository
import com.locationjoystick.core.location.MockLocationIntentBuilder
import com.locationjoystick.core.model.LatLng
import com.locationjoystick.core.model.MockLocationState
import com.locationjoystick.core.model.MockMode
import com.locationjoystick.core.model.Route
import com.locationjoystick.core.model.RouteStartConfig
import com.locationjoystick.core.model.Waypoint
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlin.math.abs

/** Runs against real device providers; it does not replace Google location services with a fake. */
@LargeTest
@HiltAndroidTest
class FusedStabilityDeviceTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var routeRepository: RouteRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var locationRepository: LocationRepository

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val samples = CopyOnWriteArrayList<Sample>()
    private val phases = JSONArray()
    private val evidence = JSONObject().put("schemaVersion", 1).put("phases", phases)
    private val listeners = mutableListOf<LocationListener>()
    private lateinit var manager: LocationManager
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var callbackThread: HandlerThread
    private var fusedCallback: LocationCallback? = null

    @Test
    fun fixedRoutePauseAndScreenOffKeepProviderLocationsConsistent() =
        runBlocking<Unit> {
            hiltRule.inject()
            evidence.put("package", context.packageName).put("startedEpochMillis", System.currentTimeMillis())
            evidence.put("status", "running")
            writeEvidence()
            manager = requireNotNull(context.getSystemService(LocationManager::class.java))
            fused = LocationServices.getFusedLocationProviderClient(context)
            callbackThread = HandlerThread("fused-stability-callbacks").apply { start() }
            var scenario: ActivityScenario<MainActivity>? = null
            var testFailure: Throwable? = null
            try {
                configureIsolatedTestData()
                scenario = ActivityScenario.launch(MainActivity::class.java)
                scenario.onActivity { activity ->
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                registerListeners()
                ContextCompat.startForegroundService(
                    context,
                    MockLocationIntentBuilder.startSpoofing(context, START.latitude, START.longitude),
                )
                awaitCondition("initial provider fixes", 30_000L) {
                    val recent = samples.filter { it.receivedNanos > SystemClock.elapsedRealtimeNanos() - FIVE_SECONDS_NANOS }
                    SOURCES.all { source ->
                        recent.any { it.source == source && LocationCompat.isMock(it.fix) && distance(it.fix, START) < 25f }
                    }
                }
                observePhase("fixed", 90, MockMode.TELEPORT, MockLocationState.RUNNING, screenOff = false)

                context.startService(
                    MockLocationIntentBuilder.startRouteReplay(
                        context,
                        ROUTE_ID,
                        2.0,
                        RouteStartConfig(isLooping = true, teleportToStart = true),
                    ),
                )
                awaitCondition("route replay starts") {
                    locationRepository.currentMode.value == MockMode.ROUTE_REPLAY &&
                        locationRepository.mockLocationState.value == MockLocationState.RUNNING
                }
                observePhase("route", 120, MockMode.ROUTE_REPLAY, MockLocationState.RUNNING, screenOff = false)

                context.startService(MockLocationIntentBuilder.pauseRouteReplay(context))
                awaitCondition("route pause") { locationRepository.mockLocationState.value == MockLocationState.PAUSED }
                observePhase("paused", 30, MockMode.ROUTE_REPLAY, MockLocationState.PAUSED, screenOff = false)

                context.startService(MockLocationIntentBuilder.resumeRouteReplay(context, 2.0))
                awaitCondition("route resumes") { locationRepository.mockLocationState.value == MockLocationState.RUNNING }
                shell("input keyevent KEYCODE_SLEEP")
                awaitCondition("screen powers off", 5_000L) { !powerManager().isInteractive }
                observePhase("route-screen-off", 120, MockMode.ROUTE_REPLAY, MockLocationState.RUNNING, screenOff = true)
                evidence.put("status", "passed")
            } catch (failure: Throwable) {
                testFailure = failure
                evidence.put("status", "failed").put("failure", failure.toString())
                throw failure
            } finally {
                try {
                    context.startService(MockLocationIntentBuilder.stopSpoofing(context))
                    awaitCondition("session stops") { locationRepository.mockLocationState.value == MockLocationState.IDLE }
                    awaitCondition("Fused mock cache clears", 15_000L) {
                        val last = withTimeout(3_000L) { fused.lastLocation.await() }
                        last == null || !LocationCompat.isMock(last)
                    }
                    evidence.put("cleanup", "Fused cache contains no mock fix after stop")
                } catch (cleanupFailure: Throwable) {
                    evidence.put("cleanupFailure", cleanupFailure.toString()).put("status", "failed")
                    if (testFailure == null) throw cleanupFailure
                } finally {
                    unregisterListeners()
                    shell("input keyevent KEYCODE_WAKEUP")
                    scenario?.close()
                    callbackThread.quitSafely()
                    routeRepository.deleteRoute(ROUTE_ID)
                    evidence.put("endedEpochMillis", System.currentTimeMillis())
                    writeEvidence()
                    Log.i(TAG, "Device verification ${evidence.optString("status")}; evidence saved locally")
                }
            }
        }

    private suspend fun configureIsolatedTestData() {
        // The fork has its own package/data directory; the installed original is not touched.
        settingsRepository.setOnboardingComplete(true)
        settingsRepository.setHideWidgetOverlay(true)
        settingsRepository.setJitterIdleRadius(0.0)
        settingsRepository.setJitterMovingRadius(0.0)
        settingsRepository.setRealismWarmupEnabled(false)
        settingsRepository.setRealismSuspendedMockingEnabled(false)
        settingsRepository.setRealismRealElevationEnabled(false)
        locationRepository.setPositionInternal(START)
        routeRepository
            .insertRoute(
                Route(
                    id = ROUTE_ID,
                    name = "Fused stability verification",
                    waypoints =
                        listOf(
                            Waypoint("stability-a", START, 0),
                            Waypoint("stability-b", LatLng(37.5865, 126.9780), 1),
                            Waypoint("stability-c", LatLng(37.5865, 126.9880), 2),
                        ),
                ),
            ).getOrThrow()
    }

    private suspend fun registerListeners() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw AssertionError("Fine location permission must be granted before verification")
        }
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val listener =
                object : LocationListener {
                    override fun onLocationChanged(location: Location) = record(provider, location)

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) = Unit

                    @Suppress("DEPRECATION") // Required by LocationListener on the API 28 verification device.
                    override fun onStatusChanged(
                        provider: String?,
                        status: Int,
                        extras: Bundle?,
                    ) = Unit
                }
            listeners += listener
            manager.requestLocationUpdates(provider, 0L, 0f, listener, callbackThread.looper)
        }
        val callback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.locations.forEach { record(FUSED, it) }
                }
            }
        fusedCallback = callback
        val request =
            LocationRequest
                .Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                .setMinUpdateIntervalMillis(500L)
                .setMaxUpdateDelayMillis(0L)
                .setWaitForAccurateLocation(false)
                .build()
        withTimeout(10_000L) { fused.requestLocationUpdates(request, callback, callbackThread.looper).await() }
    }

    private fun record(
        source: String,
        location: Location,
    ) {
        samples += Sample(source, Location(location), SystemClock.elapsedRealtimeNanos())
    }

    private suspend fun observePhase(
        name: String,
        durationSeconds: Int,
        expectedMode: MockMode,
        expectedState: MockLocationState,
        screenOff: Boolean,
    ) {
        val started = SystemClock.elapsedRealtimeNanos()
        val phase =
            JSONObject()
                .put("name", name)
                .put("durationSeconds", durationSeconds)
                .put("startedEpochMillis", System.currentTimeMillis())
                .put("expectedMode", expectedMode.name)
                .put("expectedState", expectedState.name)
                .put("screenOff", screenOff)
                .put("status", "running")
        phases.put(phase)
        writeEvidence()
        Log.i(TAG, "Starting phase $name for ${durationSeconds}s")
        try {
            repeat(durationSeconds) {
                delay(1_000L)
                assertEquals("$name movement mode", expectedMode, locationRepository.currentMode.value)
                assertEquals("$name session state", expectedState, locationRepository.mockLocationState.value)
                assertEquals("$name screen state", !screenOff, powerManager().isInteractive)
            }
            val ended = SystemClock.elapsedRealtimeNanos()
            val phaseSamples = samples.filter { it.receivedNanos in started..ended }
            val gps = phaseSamples.filter { it.source == LocationManager.GPS_PROVIDER }
            val metrics = JSONObject()
            phase.put("providers", metrics)
            for (source in SOURCES) {
                val selected = phaseSamples.filter { it.source == source }
                val providerMetrics =
                    JSONObject()
                        .put("count", selected.size)
                        .put("nonMockCount", selected.count { !LocationCompat.isMock(it.fix) })
                metrics.put(source, providerMetrics)
                assertTrue("$name requires repeated $source callbacks", selected.size >= durationSeconds / 3)
                assertTrue("$name $source must retain mock markers", selected.all { LocationCompat.isMock(it.fix) })
                val deliveries = listOf(started) + selected.map { it.receivedNanos } + ended
                val maxGapMs = deliveries.zipWithNext { a, b -> (b - a) / 1_000_000.0 }.maxOrNull() ?: 0.0
                val maxFixAgeMs = selected.maxOf { (it.receivedNanos - it.fix.elapsedRealtimeNanos) / 1_000_000.0 }
                providerMetrics.put("maxCallbackGapMs", maxGapMs).put("maxFixAgeMs", maxFixAgeMs)
                assertTrue("$name $source callback gap ${maxGapMs}ms exceeds 5s", maxGapMs <= 5_000.0)
                assertTrue("$name $source fix is stale (${maxFixAgeMs}ms)", maxFixAgeMs <= 5_000.0)
                val allGps = samples.filter { it.source == LocationManager.GPS_PROVIDER }
                val maxDistance =
                    selected.maxOf { sample ->
                        val closest = allGps.minByOrNull { abs(it.fix.elapsedRealtimeNanos - sample.fix.elapsedRealtimeNanos) }
                        requireNotNull(closest)
                        assertTrue(
                            "$name $source fix must have a GPS peer within 2s",
                            abs(closest.fix.elapsedRealtimeNanos - sample.fix.elapsedRealtimeNanos) <= 2_000_000_000L,
                        )
                        sample.fix.distanceTo(closest.fix).toDouble()
                    }
                providerMetrics.put("maxGpsDistanceMeters", maxDistance)
                assertTrue("$name $source diverged ${maxDistance}m from GPS", maxDistance <= 25.0)
            }
            val movementMeters =
                gps
                    .first()
                    .fix
                    .distanceTo(gps.last().fix)
                    .toDouble()
            phase.put("movementMeters", movementMeters)
            if (name == "route" || name == "route-screen-off") {
                assertTrue("$name must actually move while replaying", movementMeters >= 50.0)
            } else {
                assertTrue("$name must remain at a fixed point", movementMeters <= 5.0)
            }
            phase.put("status", "passed")
            Log.i(TAG, "Phase $name passed")
        } catch (failure: Throwable) {
            phase.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            phase.put("endedEpochMillis", System.currentTimeMillis())
            writeEvidence()
        }
    }

    private suspend fun awaitCondition(
        description: String,
        timeoutMillis: Long = 15_000L,
        condition: suspend () -> Boolean,
    ) {
        withTimeout(timeoutMillis) {
            while (!condition()) {
                assertFalse("$description: service entered ERROR", locationRepository.mockLocationState.value == MockLocationState.ERROR)
                delay(100L)
            }
        }
    }

    private suspend fun unregisterListeners() {
        listeners.forEach(manager::removeUpdates)
        fusedCallback?.let { callback ->
            try {
                withTimeout(5_000L) { fused.removeLocationUpdates(callback).await() }
            } catch (failure: Exception) {
                Log.e(TAG, "Could not unregister Fused verification callback", failure)
                evidence.put("listenerCleanupFailure", failure.toString())
            }
        }
    }

    private fun powerManager(): PowerManager = requireNotNull(context.getSystemService(PowerManager::class.java))

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }
    }

    private fun writeEvidence() {
        File(context.filesDir, "fused-stability-evidence.json").writeText(evidence.toString(2))
    }

    private fun distance(
        location: Location,
        point: LatLng,
    ): Float {
        val result = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, point.latitude, point.longitude, result)
        return result[0]
    }

    private data class Sample(
        val source: String,
        val fix: Location,
        val receivedNanos: Long,
    )

    companion object {
        private const val TAG = "FusedStabilityTest"
        private const val FUSED = "google-fused"
        private const val ROUTE_ID = "fused-stability-device-route"
        private const val FIVE_SECONDS_NANOS = 5_000_000_000L
        private val START = LatLng(37.5665, 126.9780)
        private val SOURCES = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, FUSED)
    }
}
