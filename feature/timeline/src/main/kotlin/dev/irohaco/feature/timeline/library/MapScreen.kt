package dev.irohaco.feature.timeline.library

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng as GoogleLatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import dev.irohaco.core.application.BrowseMap
import dev.irohaco.core.contracts.MapProvider
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.GeoBounds
import dev.irohaco.core.domain.GeoCluster
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.clusterPoints
import dev.irohaco.feature.timeline.R
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

class MapViewModel(private val browse: BrowseMap) : ViewModel() {
    /** Null while the points load. */
    val points: StateFlow<List<GeoPoint>?> = browse.changes().map { browse.located().map { it.point } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Null until the setting loads; nothing is fetched before then. */
    val allowed: StateFlow<Boolean?> = browse.allowed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val provider: StateFlow<MapProvider> = browse.provider.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapProvider.OPEN_FREE_MAP)

    fun allow(granted: Boolean) {
        viewModelScope.launch { browse.allow(granted) }
    }
}

/**
 * Photos and videos on a map, grouped with a count (PRD GAL-017). The map only loads once the user
 * allowed its provider to receive the area shown (ADR-0011); declining points to the device's map app.
 */
@Composable
fun MapScreen(viewModel: MapViewModel, googleMapsAvailable: Boolean, onOpenArea: (GeoBounds) -> Unit, onBack: () -> Unit) {
    val points by viewModel.points.collectAsStateWithLifecycle()
    val allowed by viewModel.allowed.collectAsStateWithLifecycle()
    val provider by viewModel.provider.collectAsStateWithLifecycle()
    val active = if (provider == MapProvider.GOOGLE && googleMapsAvailable) MapProvider.GOOGLE else MapProvider.OPEN_FREE_MAP
    var declined by remember { mutableStateOf(false) }
    MapContent(
        allowed = allowed,
        declined = declined,
        onAllow = { viewModel.allow(true) },
        onDecline = { declined = true },
        onBack = onBack,
        providerName = stringResource(if (active == MapProvider.GOOGLE) R.string.map_provider_google else R.string.map_provider_openfreemap),
    ) {
        val located = points.orEmpty()
        when (active) {
            MapProvider.GOOGLE -> GoogleClusterMap(located, onOpenArea)
            MapProvider.OPEN_FREE_MAP -> MapLibreClusterMap(located, onOpenArea)
        }
    }
}

@Composable
internal fun MapContent(
    allowed: Boolean?,
    declined: Boolean,
    onAllow: () -> Unit,
    onDecline: () -> Unit,
    onBack: () -> Unit,
    providerName: String,
    map: @Composable () -> Unit,
) {
    if (allowed == false && !declined) {
        AlertDialog(
            onDismissRequest = onDecline,
            title = { Text(stringResource(R.string.map_consent_title)) },
            text = { Text(stringResource(R.string.map_consent_body, providerName)) },
            confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.map_consent_allow)) } },
            dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.map_consent_decline)) } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.map_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.viewer_back)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                allowed == true -> map()
                declined -> Text(
                    stringResource(R.string.map_declined),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(IrohacoSpacing.xxl),
                )
            }
        }
    }
}

/** OpenFreeMap's style (ADR-0011), kept in one place in case the service changes. */
private const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val CLUSTERS = "irohaco-clusters"

@Composable
private fun MapLibreClusterMap(points: List<GeoPoint>, onOpenArea: (GeoBounds) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapLibre.getInstance(context); MapView(context).apply { onCreate(null) } }
    val color = MaterialTheme.colorScheme.primary.toArgb()
    val onColor = MaterialTheme.colorScheme.onPrimary.toArgb()
    val openArea by rememberUpdatedState(onOpenArea)
    var zoom by remember { mutableDoubleStateOf(2.0) }
    var map by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.setStyle(Style.Builder().fromUri(OPEN_FREE_MAP_STYLE)) { style ->
                style.addSource(GeoJsonSource(CLUSTERS))
                style.addLayer(
                    CircleLayer(CLUSTERS, CLUSTERS).withProperties(
                        PropertyFactory.circleColor(color),
                        PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.get("count"), Expression.stop(1, 12), Expression.stop(1000, 28))),
                    ),
                )
                style.addLayer(
                    SymbolLayer("$CLUSTERS-count", CLUSTERS).withProperties(
                        PropertyFactory.textField(Expression.toString(Expression.get("count"))),
                        PropertyFactory.textColor(onColor),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(true),
                    ),
                )
                m.addOnCameraIdleListener { zoom = m.cameraPosition.zoom }
                m.addOnMapClickListener { latLng ->
                    val feature = m.queryRenderedFeatures(m.projection.toScreenLocation(latLng), CLUSTERS).firstOrNull() ?: return@addOnMapClickListener false
                    openArea(
                        GeoBounds(
                            south = feature.getNumberProperty("south").toDouble(),
                            west = feature.getNumberProperty("west").toDouble(),
                            north = feature.getNumberProperty("north").toDouble(),
                            east = feature.getNumberProperty("east").toDouble(),
                        ),
                    )
                    true
                }
                map = m to style
            }
        }
    }
    // Starts with every point in view; later the user moves the map.
    LaunchedEffect(map, points.isNotEmpty()) {
        val (m, _) = map ?: return@LaunchedEffect
        if (points.size >= 2) {
            val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.latitude, it.longitude) }).build()
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96))
        } else if (points.size == 1) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].latitude, points[0].longitude), 12.0))
        }
        zoom = m.cameraPosition.zoom
    }
    LaunchedEffect(map, points) {
        snapshotFlow { zoom }.collect { z ->
            val (_, style) = map ?: return@collect
            val features = clusterPoints(points, z).map { it.toFeature() }
            style.getSourceAs<GeoJsonSource>(CLUSTERS)?.setGeoJson(FeatureCollection.fromFeatures(features))
        }
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

private fun GeoCluster.toFeature(): Feature = Feature.fromGeometry(Point.fromLngLat(center.longitude, center.latitude)).apply {
    addNumberProperty("count", count)
    addNumberProperty("south", bounds.south)
    addNumberProperty("west", bounds.west)
    addNumberProperty("north", bounds.north)
    addNumberProperty("east", bounds.east)
}

@Composable
private fun GoogleClusterMap(points: List<GeoPoint>, onOpenArea: (GeoBounds) -> Unit) {
    val camera = rememberCameraPositionState {
        position = points.firstOrNull()?.let { CameraPosition.fromLatLngZoom(GoogleLatLng(it.latitude, it.longitude), 5f) }
            ?: CameraPosition.fromLatLngZoom(GoogleLatLng(35.68, 139.76), 2f)
    }
    val clusters = remember(points, camera.position.zoom.toInt()) { clusterPoints(points, camera.position.zoom.toDouble()) }
    GoogleMap(modifier = Modifier.fillMaxSize(), cameraPositionState = camera) {
        clusters.forEach { cluster ->
            MarkerComposable(
                cluster.count,
                state = rememberUpdatedMarkerState(GoogleLatLng(cluster.center.latitude, cluster.center.longitude)),
                onClick = { onOpenArea(cluster.bounds); true },
            ) { CountBadge(cluster.count) }
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    Box(
        Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).padding(IrohacoSpacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

@IrohacoPreviews
@Composable
private fun MapConsentPreview() {
    IrohacoTheme(dynamicColor = false) {
        MapContent(allowed = false, declined = false, onAllow = {}, onDecline = {}, onBack = {}, providerName = "OpenFreeMap") {}
    }
}

@IrohacoPreviews
@Composable
private fun MapDeclinedPreview() {
    IrohacoTheme(dynamicColor = false) {
        MapContent(allowed = false, declined = true, onAllow = {}, onDecline = {}, onBack = {}, providerName = "OpenFreeMap") {}
    }
}
