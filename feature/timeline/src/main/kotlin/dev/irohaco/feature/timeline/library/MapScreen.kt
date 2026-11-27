package dev.irohaco.feature.timeline.library

import dev.irohaco.core.domain.GeoPoint
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dev.irohaco.core.application.BrowseMap
import dev.irohaco.core.contracts.LocatedAsset
import dev.irohaco.core.designsystem.component.GridThumbnail
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.GeoBounds
import dev.irohaco.core.domain.clusterPoints
import dev.irohaco.feature.timeline.R
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MapViewModel(private val browse: BrowseMap) : ViewModel() {
    /** Null while the points load; newest first. */
    val located: StateFlow<List<LocatedAsset>?> = browse.changes().map { browse.located() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Null until the setting loads; nothing is fetched before then. */
    val allowed: StateFlow<Boolean?> = browse.allowed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun allow(granted: Boolean) {
        viewModelScope.launch { browse.allow(granted) }
    }

    /** Where a place or landmark the user typed is, by the place name service; null without consent or a match. */
    suspend fun find(name: String): GeoPoint? = browse.findPlaces(name).firstOrNull()?.point
}

/**
 * Photos and videos on a map, grouped with a count (PRD GAL-017). The map only loads once the user
 * allowed OpenFreeMap to receive the area shown (ADR-0011); declining points to the device's map app.
 */
@Composable
fun MapScreen(viewModel: MapViewModel, onOpenArea: (GeoBounds) -> Unit, onBack: () -> Unit) {
    val points by viewModel.located.collectAsStateWithLifecycle()
    val allowed by viewModel.allowed.collectAsStateWithLifecycle()
    var declined by remember { mutableStateOf(false) }
    // Once the map is agreed to, ask the OS for the GPS permission too: without it no location is read
    // at all (M1-32). Returning from the system dialog resumes the app, which re-runs IndexLocations.
    val context = LocalContext.current
    val allowGps = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(allowed) {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (allowed == true && !granted) allowGps.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }
    MapContent(
        allowed = allowed,
        declined = declined,
        onAllow = { viewModel.allow(true) },
        onDecline = { declined = true },
        onBack = onBack,
        providerName = stringResource(R.string.map_provider_openfreemap),
    ) {
        MapLibreClusterMap(points.orEmpty(), onOpenArea)
    }
}

/**
 * The map as a filter: the user moves it to the part they want, starting around [lookFor] (a place or landmark the
 * user typed) or at [initial] when one was chosen, and narrows to what is in view. The photos on it show where there
 * is something to find.
 */
@Composable
fun AreaPicker(viewModel: MapViewModel, initial: GeoBounds?, lookFor: String?, onDone: (GeoBounds) -> Unit, onBack: () -> Unit) {
    val points by viewModel.located.collectAsStateWithLifecycle()
    val allowed by viewModel.allowed.collectAsStateWithLifecycle()
    var declined by remember { mutableStateOf(false) }
    // Looked up only once the map is allowed: the name service may be a server (ADR-0011).
    var found by remember { mutableStateOf<GeoPoint?>(null) }
    var lookedUp by remember { mutableStateOf(false) }
    LaunchedEffect(lookFor, allowed) {
        if (lookFor != null && allowed == true) {
            found = viewModel.find(lookFor)
            lookedUp = true
        }
    }
    var view by remember { mutableStateOf(initial) }
    val inView = remember(points, view) { view?.let { area -> points.orEmpty().count { it.point in area } } }
    MapContent(
        allowed = allowed,
        declined = declined,
        onAllow = { viewModel.allow(true) },
        onDecline = { declined = true },
        onBack = onBack,
        providerName = stringResource(R.string.map_provider_openfreemap),
        title = lookFor?.let { stringResource(R.string.timeline_place_around, it) } ?: stringResource(R.string.timeline_place_on_map),
        bottomBar = {
            val area = view
            if (allowed == true && area != null && inView != null) {
                Button(
                    onClick = { onDone(area) },
                    enabled = inView > 0,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(IrohacoSpacing.l),
                ) { Text(stringResource(R.string.timeline_place_narrow_to_view, inView)) }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            MapLibreClusterMap(points.orEmpty(), onOpenArea = null, initial = initial, center = found, onView = { view = it })
            if (lookFor != null && lookedUp && found == null) {
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp, modifier = Modifier.align(Alignment.TopCenter).padding(IrohacoSpacing.l)) {
                    Text(stringResource(R.string.timeline_place_not_found, lookFor), modifier = Modifier.padding(IrohacoSpacing.m))
                }
            }
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
    title: String = stringResource(R.string.map_title),
    bottomBar: @Composable () -> Unit = {},
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
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.viewer_back)) }
                },
            )
        },
        bottomBar = bottomBar,
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
internal const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private val MARKER_SIZE = 56.dp

/**
 * The map library draws only the map; each group is a photo marker laid over it in Compose (M1-29)
 * and placed through the map's projection, so it follows every pan and zoom. Without [onOpenArea] a marker zooms to
 * its group; [onView] hears the area in view each time the map stops.
 */
@Composable
private fun MapLibreClusterMap(
    located: List<LocatedAsset>,
    onOpenArea: ((GeoBounds) -> Unit)?,
    initial: GeoBounds? = null,
    /** Moves the map to here, close up, once it is known. */
    center: GeoPoint? = null,
    onView: (GeoBounds) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapLibre.getInstance(context); MapView(context).apply { onCreate(null) } }
    val openArea by rememberUpdatedState(onOpenArea)
    val viewed by rememberUpdatedState(onView)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var view by remember { mutableStateOf<LatLngBounds?>(null) } // the area in view when the camera last stopped
    var zoom by remember { mutableDoubleStateOf(2.0) }
    var moved by remember { mutableIntStateOf(0) } // bumped on every camera move, so the markers re-project
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
            m.setStyle(Style.Builder().fromUri(OPEN_FREE_MAP_STYLE)) {
                m.addOnCameraMoveListener { moved++ }
                m.addOnCameraIdleListener {
                    zoom = m.cameraPosition.zoom
                    view = m.projection.visibleRegion.latLngBounds.also { viewed(GeoBounds(it.latitudeSouth, it.longitudeWest, it.latitudeNorth, it.longitudeEast)) }
                    moved++
                }
                map = m
            }
        }
    }
    // Starts at the area chosen before, else with every point in view; later the user moves the map.
    LaunchedEffect(map, located.isNotEmpty()) {
        val m = map ?: return@LaunchedEffect
        if (initial != null) {
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(initial.north, initial.east, initial.south, initial.west), 0))
        } else if (located.size >= 2) {
            val bounds = LatLngBounds.Builder().includes(located.map { LatLng(it.point.latitude, it.point.longitude) }).build()
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96))
        } else if (located.size == 1) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(located[0].point.latitude, located[0].point.longitude), 12.0))
        }
    }
    LaunchedEffect(map, center) {
        val m = map ?: return@LaunchedEffect
        if (center != null) m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(center.latitude, center.longitude), 15.0))
    }
    val clusters = remember(located, zoom) { clusterPoints(located.map { it.point }, zoom) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        val m = map ?: return@Box
        // Only the groups in view get a marker; a library of tens of thousands has far more groups than fit.
        val shown = view?.let { area -> clusters.filter { area.contains(LatLng(it.center.latitude, it.center.longitude)) } }.orEmpty()
        shown.forEach { cluster ->
            key(cluster.first) {
                PhotoMarker(
                    contentUri = located[cluster.first].contentUri,
                    count = cluster.count,
                    onClick = {
                        openArea?.invoke(cluster.bounds) ?: m.animateCamera(
                            CameraUpdateFactory.newLatLngBounds(
                                LatLngBounds.from(cluster.bounds.north, cluster.bounds.east, cluster.bounds.south, cluster.bounds.west),
                                96,
                            ),
                        )
                    },
                    modifier = Modifier.offset {
                        moved // read here so a camera move only re-places the markers
                        val p = m.projection.toScreenLocation(LatLng(cluster.center.latitude, cluster.center.longitude))
                        IntOffset((p.x - MARKER_SIZE.toPx() / 2).roundToInt(), (p.y - MARKER_SIZE.toPx()).roundToInt())
                    },
                )
            }
        }
    }
}

/** A group on the map: its newest photo, with the number of photos and videos on its corner. */
@Composable
private fun PhotoMarker(contentUri: String, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = pluralStringResource(R.plurals.map_marker_description, count, count)
    Box(
        modifier
            .size(MARKER_SIZE)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = GridThumbnail(contentUri),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(2.dp, Color.White, MaterialTheme.shapes.small),
        )
        CountBadge(count, Modifier.align(Alignment.TopStart).offset(x = -IrohacoSpacing.xs, y = -IrohacoSpacing.xs))
    }
}

@Composable
private fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = IrohacoSpacing.s, vertical = IrohacoSpacing.xxs),
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
