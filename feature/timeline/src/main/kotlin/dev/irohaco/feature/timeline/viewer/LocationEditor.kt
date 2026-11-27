package dev.irohaco.feature.timeline.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.irohaco.core.contracts.FieldEdit
import dev.irohaco.core.contracts.NamedPlace
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.feature.timeline.R
import dev.irohaco.feature.timeline.library.OPEN_FREE_MAP_STYLE
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale

/**
 * A file's location on a page of its own: with the online map allowed (ADR-0011), a map whose centre is the place and
 * a search for place names; the numbers can always be typed. Clearing removes the location; the file is not changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocationEditor(
    initial: GeoPoint?,
    mapAllowed: Boolean,
    findPlaces: suspend (String) -> List<NamedPlace>,
    onDismiss: () -> Unit,
    onSave: (FieldEdit<GeoPoint>) -> Unit,
) {
    var latitude by rememberSaveable { mutableStateOf(initial?.latitude?.let(::format).orEmpty()) }
    var longitude by rememberSaveable { mutableStateOf(initial?.longitude?.let(::format).orEmpty()) }
    // Bumped when the numbers change from outside the map, so the map moves to them.
    var jump by remember { mutableStateOf(0) }
    val edit = parseLocation(latitude, longitude)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.edit_location_title)) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(painterResource(IrohacoIcons.Close), stringResource(R.string.memo_cancel)) } },
                    actions = { TextButton(onClick = { edit?.let(onSave) }, enabled = edit != null) { Text(stringResource(R.string.memo_save)) } },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
                if (mapAllowed) {
                    PickerMap(
                        center = (edit as? FieldEdit.Set)?.value ?: initial,
                        jump = jump,
                        onCenter = { latitude = format(it.latitude); longitude = format(it.longitude) },
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                    )
                }
                Column(Modifier.padding(IrohacoSpacing.l), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                    if (mapAllowed) {
                        PlaceSearch(findPlaces) { place ->
                            latitude = format(place.point.latitude)
                            longitude = format(place.point.longitude)
                            jump++
                        }
                    } else {
                        Text(stringResource(R.string.edit_location_map_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val numbers = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    Row(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                        OutlinedTextField(latitude, { latitude = it; jump++ }, label = { Text(stringResource(R.string.edit_latitude)) }, singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f))
                        OutlinedTextField(longitude, { longitude = it; jump++ }, label = { Text(stringResource(R.string.edit_longitude)) }, singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = { latitude = ""; longitude = "" }) { Text(stringResource(R.string.edit_location_clear)) }
                    Text(stringResource(R.string.edit_location_clear_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun format(value: Double) = String.format(Locale.ROOT, "%.6f", value)

/** Place names as the device's place name service finds them; it may ask a server, so only with the map allowed. */
@Composable
private fun PlaceSearch(findPlaces: suspend (String) -> List<NamedPlace>, onPick: (NamedPlace) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<NamedPlace>?>(null) }
    val scope = rememberCoroutineScope()
    val search = { if (query.isNotBlank()) scope.launch { found = findPlaces(query.trim()) } }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(stringResource(R.string.edit_location_search)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }),
        trailingIcon = { IconButton(onClick = { search() }) { Icon(painterResource(IrohacoIcons.Search), stringResource(R.string.edit_location_search)) } },
        modifier = Modifier.fillMaxWidth(),
    )
    found?.let { places ->
        if (places.isEmpty()) Text(stringResource(R.string.edit_location_none), style = MaterialTheme.typography.bodySmall)
        places.forEach { place ->
            ListItem(
                headlineContent = { Text(place.name) },
                trailingContent = { TextButton(onClick = { onPick(place); found = null }) { Text(stringResource(R.string.edit_location_use)) } },
            )
        }
    }
}

/** A map whose centre is the place: the user moves the map under a fixed pin, as map apps let one pick a spot. */
@Composable
private fun PickerMap(center: GeoPoint?, jump: Int, onCenter: (GeoPoint) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapLibre.getInstance(context); MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // Whether the camera moves because the user drags it, set and read by the map's listeners.
    val byUser = remember { booleanArrayOf(false) }
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
        mapView.onStart()
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.setStyle(Style.Builder().fromUri(OPEN_FREE_MAP_STYLE)) {
                m.addOnCameraIdleListener {
                    // Only a move the user made changes the numbers; a jump to typed numbers lands where they say.
                    val target = m.cameraPosition.target
                    if (target != null && byUser[0]) onCenter(GeoPoint(target.latitude, target.longitude))
                    byUser[0] = false
                }
                m.addOnCameraMoveStartedListener { reason -> byUser[0] = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE }
                map = m
            }
        }
    }
    LaunchedEffect(map, jump) {
        val m = map ?: return@LaunchedEffect
        val at = center ?: return@LaunchedEffect
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(at.latitude, at.longitude), if (m.cameraPosition.zoom < 10) 14.0 else m.cameraPosition.zoom))
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // The pin's point is its bottom, so it sits half its height above the centre.
        Icon(
            painterResource(IrohacoIcons.LocationOn),
            contentDescription = null,
            tint = Color(0xFFB3261E),
            modifier = Modifier.size(40.dp).offset(y = (-20).dp),
        )
    }
}
