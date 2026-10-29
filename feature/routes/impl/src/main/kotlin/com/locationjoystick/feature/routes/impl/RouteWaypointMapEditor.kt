package com.locationjoystick.feature.routes.impl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.locationjoystick.core.common.constants.AppConstants
import com.locationjoystick.core.designsystem.LjIcons
import com.locationjoystick.core.designsystem.component.LjScaffold
import com.locationjoystick.core.map.geojson.buildLineGeoJson
import com.locationjoystick.core.map.geojson.buildPositionGeoJson
import com.locationjoystick.core.map.geojson.buildWaypointsGeoJson
import com.locationjoystick.core.map.maplibre.CreatorLayerSources
import com.locationjoystick.core.map.maplibre.MapLibreLifecycleBridge
import com.locationjoystick.core.map.maplibre.addCreatorLayers
import com.locationjoystick.core.map.maplibre.applyZoomBounds
import com.locationjoystick.core.map.maplibre.rememberMapView
import com.locationjoystick.core.map.projection.projection
import com.locationjoystick.core.map.ui.MapAttribution
import com.locationjoystick.core.model.LatLng
import com.locationjoystick.core.model.MapTileSource
import com.locationjoystick.core.model.Route
import com.locationjoystick.core.model.Waypoint
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.util.Locale
import org.maplibre.android.geometry.LatLng as MapLatLng

/** Draft-only map selection. The repository is written only after the explicit Save action. */
@Composable
internal fun RouteWaypointMapEditor(
    route: Route,
    waypoint: Waypoint,
    tileSource: MapTileSource,
    onSave: suspend (LatLng) -> Boolean,
    onClose: () -> Unit,
) {
    var latitude by rememberSaveable(waypoint.id) { mutableDoubleStateOf(waypoint.position.latitude) }
    var longitude by rememberSaveable(waypoint.id) { mutableDoubleStateOf(waypoint.position.longitude) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    var layers by remember { mutableStateOf<CreatorLayerSources?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var mapReference by remember { mutableStateOf<MapLibreMap?>(null) }
    var appliedTileSource by remember { mutableStateOf<MapTileSource?>(null) }
    val selected = LatLng(latitude, longitude)
    val currentSelected by rememberUpdatedState(selected)
    val currentRoute by rememberUpdatedState(route)
    val currentTileSource by rememberUpdatedState(tileSource)
    val scope = rememberCoroutineScope()
    val mapView = rememberMapView()
    val mapDescription = stringResource(R.string.route_waypoint_edit_map_cd)
    val mapState = stringResource(if (mapReady) R.string.route_waypoint_edit_map_ready else R.string.route_waypoint_edit_map_loading)

    MapLibreLifecycleBridge(mapView)
    BackHandler { if (!saving) onClose() }

    fun updateLayers() {
        val preview = currentRoute.waypoints.map { if (it.id == waypoint.id) currentSelected else it.position }
        val projection = currentTileSource.projection
        layers?.segmentsSource?.setGeoJson(buildLineGeoJson(preview.map(projection::toMap)))
        layers?.waypointsSource?.setGeoJson(buildWaypointsGeoJson(preview.map(projection::toMap)))
        layers?.currentPosSource?.setGeoJson(buildPositionGeoJson(projection.toMap(currentSelected)))
    }

    fun applyStyle(map: MapLibreMap) {
        val source = currentTileSource
        mapReady = false
        layers = null
        appliedTileSource = source
        map.applyZoomBounds(source)
        map.setStyle(Style.Builder().fromUri(AppConstants.MapConstants.EMPTY_MAP_STYLE_URI)) { style ->
            if (source == currentTileSource) {
                layers =
                    style.addCreatorLayers(
                        tileSource = source,
                        currentPosGeoJson = buildPositionGeoJson(source.projection.toMap(currentSelected)),
                    )
                updateLayers()
                mapReady = true
            }
        }
    }

    LaunchedEffect(tileSource) {
        val map = mapReference ?: return@LaunchedEffect
        val previous = appliedTileSource ?: return@LaunchedEffect
        if (previous != tileSource) {
            val center = map.cameraPosition.target?.let { previous.projection.fromMap(LatLng(it.latitude, it.longitude)) }
            applyStyle(map)
            center?.let {
                val projected = tileSource.projection.toMap(it)
                map.moveCamera(CameraUpdateFactory.newLatLng(MapLatLng(projected.latitude, projected.longitude)))
            }
        }
    }

    LjScaffold(
        title = stringResource(R.string.route_detail_waypoint_number, waypoint.orderIndex + 1),
        isSpoofing = false,
        onToggleSpoofing = {},
        showSpoofToggle = false,
        onNavigationClick = { if (!saving) onClose() },
        navigationIcon = LjIcons.ArrowBack,
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.route_waypoint_edit_hint), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude),
                        modifier = Modifier.testTag("waypoint_selected_coordinates"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (saveError) {
                        Text(
                            stringResource(R.string.route_waypoint_edit_save_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onClose, enabled = !saving, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.route_creator_cancel))
                        }
                        Button(
                            enabled = !saving && mapReady,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                saving = true
                                saveError = false
                                scope.launch {
                                    try {
                                        if (onSave(selected)) onClose() else saveError = true
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(if (saving) R.string.route_waypoint_edit_saving else R.string.route_creator_save))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = {
                    mapView.apply {
                        getMapAsync { map ->
                            mapReference = map
                            map.uiSettings.isAttributionEnabled = false
                            map.uiSettings.isLogoEnabled = false
                            val initial = currentTileSource.projection.toMap(currentSelected)
                            map.cameraPosition =
                                CameraPosition
                                    .Builder()
                                    .target(MapLatLng(initial.latitude, initial.longitude))
                                    .zoom(AppConstants.MapConstants.DEFAULT_ZOOM)
                                    .build()
                            applyStyle(map)
                            map.addOnMapClickListener { point ->
                                if (!saving) {
                                    val wgs = currentTileSource.projection.fromMap(LatLng(point.latitude, point.longitude))
                                    latitude = wgs.latitude
                                    longitude = wgs.longitude
                                }
                                true
                            }
                        }
                    }
                },
                update = { updateLayers() },
                modifier =
                    Modifier.fillMaxSize().testTag("waypoint_location_map").semantics {
                        contentDescription = mapDescription
                        stateDescription = mapState
                    },
            )
            MapAttribution(tileSource, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RouteWaypointMapEditorPreview() {
    val point = Waypoint("preview-point", LatLng(48.8566, 2.3522), 0)
    RouteWaypointMapEditor(
        route = Route("preview-route", "Preview", waypoints = listOf(point)),
        waypoint = point,
        tileSource = MapTileSource.DEFAULT,
        onSave = { true },
        onClose = {},
    )
}
