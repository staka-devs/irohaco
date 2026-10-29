package dev.irohaco.feature.timeline.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import dev.irohaco.core.contracts.FieldEdit
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.feature.timeline.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A date, then a time; the seconds stay as they were. Times are in the device's time zone, as MediaStore keeps them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TakenAtDialog(initialMs: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val initial = Instant.ofEpochMilli(initialMs).atZone(zone).toLocalDateTime()
    // The date picker counts days in UTC.
    val date = rememberDatePickerState(initialSelectedDateMillis = initial.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    val time = rememberTimePickerState(initial.hour, initial.minute, android.text.format.DateFormat.is24HourFormat(LocalContext.current))
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    if (!pickingTime) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { pickingTime = true }, enabled = date.selectedDateMillis != null) { Text(stringResource(R.string.edit_next)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memo_cancel)) } },
        ) { DatePicker(date) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.details_taken)) },
            text = { TimePicker(time) },
            confirmButton = {
                TextButton(onClick = {
                    val day = Instant.ofEpochMilli(date.selectedDateMillis ?: return@TextButton).atZone(ZoneOffset.UTC).toLocalDate()
                    onSave(takenAt(day, time.hour, time.minute, initial.second, zone))
                }) { Text(stringResource(R.string.memo_save)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memo_cancel)) } },
        )
    }
}

internal fun takenAt(day: LocalDate, hour: Int, minute: Int, second: Int, zone: ZoneId): Long =
    LocalDateTime.of(day.year, day.month, day.dayOfMonth, hour, minute, second).atZone(zone).toInstant().toEpochMilli()

/** Latitude and longitude as numbers; clearing both removes the location. */
@Composable
internal fun LocationDialog(initial: GeoPoint?, onDismiss: () -> Unit, onSave: (FieldEdit<GeoPoint>) -> Unit) {
    var latitude by rememberSaveable { mutableStateOf(initial?.latitude?.toString().orEmpty()) }
    var longitude by rememberSaveable { mutableStateOf(initial?.longitude?.toString().orEmpty()) }
    val edit = parseLocation(latitude, longitude)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.details_location)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                val numbers = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                OutlinedTextField(latitude, { latitude = it }, label = { Text(stringResource(R.string.edit_latitude)) }, singleLine = true, keyboardOptions = numbers, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(longitude, { longitude = it }, label = { Text(stringResource(R.string.edit_longitude)) }, singleLine = true, keyboardOptions = numbers, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.edit_location_clear_hint))
            }
        },
        confirmButton = { TextButton(onClick = { edit?.let(onSave) }, enabled = edit != null) { Text(stringResource(R.string.memo_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memo_cancel)) } },
    )
}

/** Both blank removes the location; null when the numbers are not a place on Earth. */
internal fun parseLocation(latitude: String, longitude: String): FieldEdit<GeoPoint>? {
    if (latitude.isBlank() && longitude.isBlank()) return FieldEdit.Removed
    val lat = latitude.trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
    val lon = longitude.trim().toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
    return FieldEdit.Set(GeoPoint(lat, lon))
}

/** Clearing the text removes the description. */
@Composable
internal fun DescriptionDialog(initial: String, onDismiss: () -> Unit, onSave: (FieldEdit<String>) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.details_description)) },
        text = { OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(onClick = { onSave(if (text.isBlank()) FieldEdit.Removed else FieldEdit.Set(text.trim())) }) { Text(stringResource(R.string.memo_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memo_cancel)) } },
    )
}
