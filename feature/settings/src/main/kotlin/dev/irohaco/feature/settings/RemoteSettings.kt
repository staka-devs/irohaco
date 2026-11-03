package dev.irohaco.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.irohaco.core.application.ManageEgress
import dev.irohaco.core.application.ManageEgress.UrlKind
import dev.irohaco.core.contracts.EgressGrant
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteProtocol
import dev.irohaco.core.contracts.TaskType
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

/**
 * The remote connections, and per task each kind of content each may get; all off at first (API-007,
 * ADR-0002). Adding one keeps its key in the Keystore and still sends nothing (API-008).
 */
@Composable
internal fun RemoteSettings(
    connections: List<RemoteConnection>,
    grants: Set<EgressGrant>,
    onSet: (EgressGrant, Boolean) -> Unit,
    onAdd: (RemoteProtocol, String, String) -> Unit = { _, _, _ -> },
    onRemove: (String) -> Unit = {},
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<RemoteConnection?>(null) }
    if (adding) AddConnectionDialog(onDismiss = { adding = false }) { protocol, url, key -> adding = false; onAdd(protocol, url, key) }
    removing?.let { connection ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.settings_remote_remove_title)) },
            text = { Text(stringResource(R.string.settings_remote_remove_body)) },
            confirmButton = {
                TextButton(onClick = { removing = null; onRemove(connection.id) }) { Text(stringResource(R.string.settings_ai_delete)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.settings_ai_cancel)) } },
        )
    }
    SectionTitle(R.string.settings_remote)
    ListItem {
        Text(
            stringResource(if (connections.isEmpty()) R.string.settings_remote_none else R.string.settings_remote_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    connections.forEach { connection ->
        ListItem(
            supportingContent = { Text(connection.baseUrl) },
            trailingContent = {
                IconButton(onClick = { removing = connection }) {
                    Icon(painterResource(IrohacoIcons.Delete), stringResource(R.string.settings_remote_remove))
                }
            },
        ) { Text(connection.protocol.displayName, style = MaterialTheme.typography.titleMedium) }
        ManageEgress.offered.forEach { (task, payloads) ->
            Text(
                stringResource(TASK_LABELS.getValue(task)),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = IrohacoSpacing.l, top = IrohacoSpacing.s),
            )
            payloads.forEach { payload ->
                val grant = EgressGrant(connection.id, task, payload)
                val on = grant in grants
                ListItem(
                    trailingContent = { Switch(checked = on, onCheckedChange = null) },
                    modifier = Modifier.toggleable(on, role = Role.Switch) { onSet(grant, it) },
                ) { Text(stringResource(PAYLOAD_LABELS.getValue(payload))) }
            }
        }
    }
    ListItem(
        leadingContent = { Icon(painterResource(IrohacoIcons.Add), contentDescription = null) },
        modifier = Modifier.selectable(selected = false, role = Role.Button) { adding = true },
    ) { Text(stringResource(R.string.settings_remote_add)) }
}

/** The format, URL and key of a new connection; plain http is added only once the user accepts it is unencrypted (PRD §10). */
@Composable
private fun AddConnectionDialog(onDismiss: () -> Unit, onAdd: (RemoteProtocol, String, String) -> Unit) {
    var protocol by rememberSaveable { mutableStateOf(RemoteProtocol.OPENAI_CHAT) }
    var url by rememberSaveable { mutableStateOf(protocol.defaultBaseUrl) }
    // Not saved: the key stays out of the saved instance state.
    var key by remember { mutableStateOf("") }
    var plainAccepted by rememberSaveable { mutableStateOf(false) }
    val kind = ManageEgress.urlKind(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_remote_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                Column(Modifier.selectableGroup()) {
                    RemoteProtocol.entries.forEach { entry ->
                        ListItem(
                            leadingContent = { RadioButton(selected = entry == protocol, onClick = null) },
                            modifier = Modifier.selectable(selected = entry == protocol, role = Role.RadioButton) {
                                protocol = entry
                                url = entry.defaultBaseUrl
                            },
                        ) { Text(entry.displayName) }
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; plainAccepted = false },
                    label = { Text(stringResource(R.string.settings_remote_url)) },
                    isError = kind == UrlKind.INVALID,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kind == UrlKind.PLAIN_HTTP) {
                    Text(stringResource(R.string.settings_remote_http_warning), color = MaterialTheme.colorScheme.error)
                    ListItem(
                        leadingContent = { Checkbox(checked = plainAccepted, onCheckedChange = null) },
                        modifier = Modifier.toggleable(plainAccepted, role = Role.Checkbox) { plainAccepted = it },
                    ) { Text(stringResource(R.string.settings_remote_http_accept)) }
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.settings_remote_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.settings_remote_billing), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = key.isNotBlank() && (kind == UrlKind.HTTPS || kind == UrlKind.PLAIN_HTTP && plainAccepted),
                onClick = { onAdd(protocol, url, key) },
            ) { Text(stringResource(R.string.settings_remote_add_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_ai_cancel)) } },
    )
}

private val TASK_LABELS = mapOf(
    TaskType.SEARCH_QUERY to R.string.settings_remote_task_search,
    TaskType.IMAGE_INDEX to R.string.settings_remote_task_image_index,
    TaskType.VIDEO_INDEX to R.string.settings_remote_task_video_index,
    TaskType.RAG to R.string.settings_remote_task_rag,
)

private val PAYLOAD_LABELS = mapOf(
    EgressPayload.QUERY_TEXT to R.string.settings_remote_query_text,
    EgressPayload.IMAGE_PIXELS to R.string.settings_remote_image_pixels,
    EgressPayload.VIDEO_FRAMES to R.string.settings_remote_video_frames,
    EgressPayload.OCR_TEXT to R.string.settings_remote_ocr_text,
    EgressPayload.USER_ANNOTATIONS to R.string.settings_remote_user_annotations,
)

@IrohacoPreviews
@Composable
private fun RemoteSettingsPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RemoteSettings(
                    listOf(RemoteConnection("openai", RemoteProtocol.OPENAI_RESPONSES, "https://api.example.com/v1")),
                    setOf(EgressGrant("openai", TaskType.RAG, EgressPayload.QUERY_TEXT)),
                    { _, _ -> },
                )
            }
        }
    }
}

@IrohacoPreviews
@Composable
private fun RemoteSettingsNonePreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface { Column { RemoteSettings(emptyList(), emptySet(), { _, _ -> }) } }
    }
}
