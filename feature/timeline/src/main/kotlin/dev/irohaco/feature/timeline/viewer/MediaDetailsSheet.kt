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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.InputChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import dev.irohaco.core.contracts.FieldEdit
import dev.irohaco.core.contracts.HistoryReview
import dev.irohaco.core.contracts.MetadataField
import dev.irohaco.core.contracts.geoPointOfMetadataText
import dev.irohaco.core.contracts.MetadataEdits
import dev.irohaco.core.contracts.Tag
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import dev.irohaco.core.designsystem.icon.IrohacoIcons
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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
    rating: Int? = null,
    onRate: (Int?) -> Unit = {},
    tags: List<Tag> = emptyList(),
    onAddTag: () -> Unit = {},
    onRemoveTag: (String) -> Unit = {},
    memos: @Composable () -> Unit = {},
    edits: MetadataEdits = MetadataEdits(),
    onEdit: (MetadataField) -> Unit = {},
    onRevert: (MetadataField) -> Unit = {},
    onKeep: (MetadataField) -> Unit = {},
    fileActions: @Composable () -> Unit = {},
    historyPlace: @Composable () -> Unit = {},
) {
    if (state == ViewerViewModel.Details.Hidden) return
    IrohacoBottomSheet(onDismiss = onDismiss) {
        when (state) {
            ViewerViewModel.Details.Loading -> ExpressiveLoading(Modifier.padding(IrohacoSpacing.xl))
            is ViewerViewModel.Details.Shown -> MediaDetailsContent(state.result, kind, onAllowLocation, rating, onRate, tags, onAddTag, onRemoveTag, memos, edits, onEdit, onRevert, onKeep, fileActions, historyPlace)
            ViewerViewModel.Details.Hidden -> Unit
        }
    }
}

@Composable
internal fun MediaDetailsContent(
    result: MediaDetailsResult,
    kind: MediaKind,
    onAllowLocation: () -> Unit,
    rating: Int? = null,
    onRate: (Int?) -> Unit = {},
    tags: List<Tag> = emptyList(),
    onAddTag: () -> Unit = {},
    onRemoveTag: (String) -> Unit = {},
    memos: @Composable () -> Unit = {},
    edits: MetadataEdits = MetadataEdits(),
    onEdit: (MetadataField) -> Unit = {},
    onRevert: (MetadataField) -> Unit = {},
    onKeep: (MetadataField) -> Unit = {},
    fileActions: @Composable () -> Unit = {},
    historyPlace: @Composable () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = IrohacoSpacing.l)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m),
    ) {
        Text(stringResource(R.string.details_title), style = MaterialTheme.typography.titleLarge)
        RatingRow(rating, onRate)
        TagsRow(tags, onAddTag, onRemoveTag)
        memos()
        when (result) {
            MediaDetailsResult.FileUnavailable -> Text(stringResource(R.string.details_file_unavailable), style = MaterialTheme.typography.bodyLarge)
            is MediaDetailsResult.Read -> {
                DetailRows(result.details, kind, onAllowLocation, edits, onEdit, onRevert, onKeep, historyPlace)
                fileActions()
            }
        }
    }
}

@Composable
private fun DetailRows(
    details: MediaDetails,
    kind: MediaKind,
    onAllowLocation: () -> Unit,
    edits: MetadataEdits,
    onEdit: (MetadataField) -> Unit,
    onRevert: (MetadataField) -> Unit,
    onKeep: (MetadataField) -> Unit,
    historyPlace: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // The file's value when the field was edited, for a field another app changed since; absent without a conflict.
    fun <T> base(field: MetadataField, parse: (String) -> T): Detail<T>? =
        if (field in edits.conflicts) edits.conflicts[field]?.let { Detail.Value(parse(it)) } ?: Detail.Missing else null
    Row(R.string.details_file, Detail.Value(details.fileName)) { it }
    Row(R.string.details_folder, details.folder) { it }
    Row(R.string.details_size, details.sizeBytes) { Formatter.formatShortFileSize(context, it) }
    Row(R.string.details_type, details.mimeType) { it }
    Row(R.string.details_pixels, pixels(details)) { it }
    EditableRow(
        R.string.details_taken, details.takenAt, edits.takenAtMs?.let { FieldEdit.Set(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()) },
        base(MetadataField.TAKEN, LocalDateTime::parse), { onEdit(MetadataField.TAKEN) }, { onRevert(MetadataField.TAKEN) }, { onKeep(MetadataField.TAKEN) },
    ) { DATE_TIME.format(it) }
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
    EditableRow(
        R.string.details_location, details.location, edits.location, base(MetadataField.LOCATION, ::geoPointOfMetadataText),
        { onEdit(MetadataField.LOCATION) }, { onRevert(MetadataField.LOCATION) }, { onKeep(MetadataField.LOCATION) },
    ) {
        "%.5f, %.5f".format(it.latitude, it.longitude)
    }
    if (details.location == Detail.NoPermission && edits.location == null) {
        TextButton(onClick = onAllowLocation) { Text(stringResource(R.string.details_allow_location)) }
    }
    historyPlace()
    if (kind == MediaKind.IMAGE) {
        Row(R.string.details_headline, details.headline) { it }
        Row(R.string.details_keywords, details.keywords) { it.joinToString() }
        Row(R.string.details_creator, details.creator) { it }
        Row(R.string.details_copyright, details.copyright) { it }
    }
    EditableRow(
        R.string.details_description, details.description, edits.description, base(MetadataField.DESCRIPTION) { it },
        { onEdit(MetadataField.DESCRIPTION) }, { onRevert(MetadataField.DESCRIPTION) }, { onKeep(MetadataField.DESCRIPTION) },
    ) { it }
}

/**
 * A row the user can change in the DB (MET-002): the edited value is marked as such over the file's own,
 * which stays one tap away (MET-005). When another app changed the file since the edit, the row also shows
 * the file's value at the edit, and the user keeps one of the two (INT-002).
 */
@Composable
private fun <T> EditableRow(
    label: Int,
    file: Detail<T>,
    edit: FieldEdit<T>?,
    base: Detail<T>?,
    onEdit: () -> Unit,
    onRevert: () -> Unit,
    onKeep: () -> Unit,
    format: (T) -> String,
) {
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(label, if (edit == null) file else (edit as? FieldEdit.Set)?.let { Detail.Value(it.value) } ?: Detail.Missing, format)
            if (edit != null) {
                val note = MaterialTheme.typography.bodySmall
                Text(stringResource(R.string.details_edited, detailText(file, format).first), style = note, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (base != null) {
                    Text(stringResource(R.string.details_conflict, detailText(base, format).first), style = note, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onKeep) { Text(stringResource(R.string.details_keep_edit)) }
                }
                TextButton(onClick = onRevert) { Text(stringResource(R.string.details_revert)) }
            }
        }
        IconButton(onClick = onEdit, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(painterResource(IrohacoIcons.Edit), contentDescription = stringResource(R.string.details_edit, stringResource(label)))
        }
    }
}

/** Five stars (PRD GAL-007). Tapping the current rating again clears it. */
@Composable
private fun RatingRow(rating: Int?, onRate: (Int?) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.details_rating), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Row(Modifier.selectableGroup()) {
            for (stars in 1..5) {
                val filled = rating != null && stars <= rating
                IconToggleButton(
                    checked = rating == stars,
                    onCheckedChange = { onRate(if (rating == stars) null else stars) },
                ) {
                    Icon(
                        painterResource(if (filled) IrohacoIcons.StarFilled else IrohacoIcons.Star),
                        contentDescription = stringResource(if (rating == stars) R.string.details_clear_rating else R.string.details_rate, stars),
                        tint = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The asset's tags as chips; each chip removes its tag (GAL-007). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsRow(tags: List<Tag>, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.details_tags), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
            tags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = { onRemove(tag.tagId) },
                    label = { Text(tag.name) },
                    trailingIcon = { Icon(painterResource(IrohacoIcons.Close), stringResource(R.string.details_remove_tag, tag.name)) },
                )
            }
            AssistChip(
                onClick = onAdd,
                label = { Text(stringResource(R.string.tags_add)) },
                leadingIcon = { Icon(painterResource(IrohacoIcons.NewLabel), contentDescription = null) },
            )
        }
    }
}

/** A label over its value, or over why there is no value (missing, withheld, unreadable). */
@Composable
private fun <T> Row(label: Int, detail: Detail<T>, format: (T) -> String) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val (text, muted) = detailText(detail, format)
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A place from the location history, not yet confirmed (#213, ADR-0029); confirming sets it as an edit. */
@Composable
internal fun HistoryPlaceRow(review: HistoryReview, onDecide: (confirmed: Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(R.string.search_place_history, Detail.Value(review)) { it.placeName ?: "%.5f, %.5f".format(it.point.latitude, it.point.longitude) }
        androidx.compose.foundation.layout.Row {
            TextButton(onClick = { onDecide(false) }) { Text(stringResource(R.string.history_reject)) }
            TextButton(onClick = { onDecide(true) }) { Text(stringResource(R.string.history_confirm)) }
        }
    }
}

/** The value, or why there is none; the flag mutes the latter. */
@Composable
private fun <T> detailText(detail: Detail<T>, format: (T) -> String): Pair<String, Boolean> = when (detail) {
    is Detail.Value -> format(detail.value) to false
    Detail.Missing -> stringResource(R.string.details_missing) to true
    Detail.NoPermission -> stringResource(R.string.details_no_permission) to true
    Detail.Unreadable -> stringResource(R.string.details_unreadable) to true
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
                rating = 3,
                tags = listOf(Tag("t1", "harbour", 0), Tag("t2", "Trip", 0)),
                edits = MetadataEdits(description = FieldEdit.Set("Harbour at dawn")),
            )
        }
    }
}
