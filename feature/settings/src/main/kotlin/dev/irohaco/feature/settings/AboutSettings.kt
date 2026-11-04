package dev.irohaco.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

/** One library in the app and the licenses it comes under. */
data class LibraryLicense(val name: String, val version: String?, val licenses: List<String>, val text: String?)

/** The libraries the build recorded (ADR-0033), by name; an unreadable list shows none rather than failing the screen. */
internal fun parseLicenses(json: String): List<LibraryLicense> = runCatching {
    Libs.Builder().withJson(json).build().libraries.map { library ->
        LibraryLicense(
            name = library.name,
            version = library.artifactVersion,
            licenses = library.licenses.map { it.name },
            // A license outside SPDX (a vendor's terms) has no text in the list; its address stands in for it.
            text = library.licenses.joinToString("\n\n") { license ->
                license.licenseContent?.takeIf(String::isNotBlank) ?: listOfNotNull(license.name, license.url).joinToString("\n")
            },
        )
    }.sortedBy { it.name.lowercase() }
}.getOrDefault(emptyList())

/**
 * Settings > About (ADR-0033): the app's own license, and every library in it with its license. A
 * library's license text opens on a tap.
 */
@Composable
internal fun AboutSettings(libraries: List<LibraryLicense>) {
    var open by remember { mutableStateOf<LibraryLicense?>(null) }
    open?.let { library ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(library.name) },
            text = {
                Text(
                    library.text?.ifBlank { null } ?: library.licenses.joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text(stringResource(R.string.settings_remote_model_done)) } },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
        SectionTitle(R.string.settings_about_license)
        Text(
            stringResource(R.string.settings_about_license_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = IrohacoSpacing.l),
        )
        SectionTitle(R.string.settings_about_libraries)
        libraries.forEach { library ->
            ListItem(
                supportingContent = { Text(listOfNotNull(library.version, library.licenses.joinToString().ifEmpty { null }).joinToString(" · ")) },
                modifier = Modifier.clickable { open = library },
            ) { Text(library.name) }
        }
    }
}

@IrohacoPreviews
@Composable
private fun AboutSettingsPreview() {
    IrohacoTheme {
        AboutSettings(
            listOf(
                LibraryLicense("Bouncy Castle Provider", "1.81", listOf("Bouncy Castle Licence"), null),
                LibraryLicense("Room", "2.8.5", listOf("Apache License 2.0"), null),
            ),
        )
    }
}
