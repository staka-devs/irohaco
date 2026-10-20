package dev.irohaco.feature.timeline.viewer

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import dev.irohaco.core.designsystem.component.IrohacoBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.irohaco.core.designsystem.component.ExpressiveLoading
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.Detail
import dev.irohaco.core.domain.MediaDetails
import dev.irohaco.core.domain.MediaDetailsResult
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.R
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** The details of the shown photo or video in a bottom sheet (PRD MET-001, DESIGN §3). */
@Composable
internal fun MediaDetailsSheet(
    state: ViewerViewModel.Details,
    kind: MediaKind,
    onDismiss: () -> Unit,
    onAllowLocation: () -> Unit,
) {
    if (state == ViewerViewModel.Details.Hidden) return
    IrohacoBottomSheet(onDismiss = onDismiss) {
        when (state) {
            ViewerViewModel.Details.Loading -> ExpressiveLoading(Modifier.padding(IrohacoSpacing.xl))
            is ViewerViewModel.Details.Shown -> MediaDetailsContent(state.result, kind, onAllowLocation)
            ViewerViewModel.Details.Hidden -> Unit
        }
    }
}

@Composable
internal fun MediaDetailsContent(result: MediaDetailsResult, kind: MediaKind, onAllowLocation: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = IrohacoSpacing.l)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m),
    ) {
        Text(stringResource(R.string.details_title), style = MaterialTheme.typography.titleLarge)
        when (result) {
            MediaDetailsResult.FileUnavailable -> Text(stringResource(R.string.details_file_unavailable), style = MaterialTheme.typography.bodyLarge)
            is MediaDetailsResult.Read -> DetailRows(result.details, kind, onAllowLocation)
        }
    }
}

@Composable
private fun DetailRows(details: MediaDetails, kind: MediaKind, onAllowLocation: () -> Unit) {
    val context = LocalContext.current
    Row(R.string.details_file, Detail.Value(details.fileName)) { it }
    Row(R.string.details_folder, details.folder) { it }
    Row(R.string.details_size, details.sizeBytes) { Formatter.formatShortFileSize(context, it) }
    Row(R.string.details_type, details.mimeType) { it }
    Row(R.string.details_pixels, pixels(details)) { it }
    Row(R.string.details_taken, details.takenAt) { DATE_TIME.format(it) }
    if (kind == MediaKind.VIDEO) {
        Row(R.string.details_duration, details.durationMs) { duration(it) }
    } else {
        Row(R.string.details_camera, details.camera) { it }
        Row(R.string.details_lens, details.lens) { it }
        Row(R.string.details_aperture, details.fNumber) { "f/%.1f".format(it) }
        Row(R.string.details_shutter, details.exposureSeconds) { shutter(it) }
        Row(R.string.details_iso, details.iso) { "ISO $it" }
        Row(R.string.details_focal_length, details.focalLengthMm) { "%.0f mm".format(it) }
    }
    Row(R.string.details_location, details.location) { "%.5f, %.5f".format(it.latitude, it.longitude) }
    if (details.location == Detail.NoPermission) {
        TextButton(onClick = onAllowLocation) { Text(stringResource(R.string.details_allow_location)) }
    }
    if (kind == MediaKind.IMAGE) {
        Row(R.string.details_description, details.description) { it }
        Row(R.string.details_keywords, details.keywords) { it.joinToString() }
    }
}

/** A label over its value, or over why there is no value (missing, withheld, unreadable). */
@Composable
private fun <T> Row(label: Int, detail: Detail<T>, format: (T) -> String) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val (text, muted) = when (detail) {
            is Detail.Value -> format(detail.value) to false
            Detail.Missing -> stringResource(R.string.details_missing) to true
            Detail.NoPermission -> stringResource(R.string.details_no_permission) to true
            Detail.Unreadable -> stringResource(R.string.details_unreadable) to true
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)

private fun pixels(details: MediaDetails): Detail<String> {
    val w = details.widthPx
    val h = details.heightPx
    return if (w is Detail.Value && h is Detail.Value) Detail.Value("${w.value} × ${h.value}") else Detail.Missing
}

internal fun duration(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}

/** 1/250 s for short exposures, 2.5 s for long ones. */
internal fun shutter(seconds: Double): String =
    if (seconds < 1) "1/${(1 / seconds).roundToInt()} s" else "%.1f s".format(seconds)

@IrohacoPreviews
@Composable
private fun MediaDetailsPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            MediaDetailsContent(
                MediaDetailsResult.Read(
                    MediaDetails(
                        fileName = "IMG_0420.jpg", folder = Detail.Value("DCIM/Camera/"), sizeBytes = Detail.Value(2_480_000),
                        mimeType = Detail.Value("image/jpeg"), widthPx = Detail.Value(4032), heightPx = Detail.Value(3024),
                        takenAt = Detail.Value(LocalDateTime.of(2026, 10, 20, 9, 15, 30)), durationMs = Detail.Missing,
                        camera = Detail.Value("Maker Model 7"), lens = Detail.Missing, fNumber = Detail.Value(1.8),
                        exposureSeconds = Detail.Value(0.004), iso = Detail.Value(200), focalLengthMm = Detail.Value(26.0),
                        location = Detail.NoPermission, description = Detail.Unreadable, keywords = Detail.Value(listOf("harbour", "dawn")),
                    ),
                ),
                MediaKind.IMAGE,
                onAllowLocation = {},
            )
        }
    }
}
