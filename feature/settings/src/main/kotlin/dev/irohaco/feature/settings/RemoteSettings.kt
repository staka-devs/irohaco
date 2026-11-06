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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.irohaco.core.application.ManageEgress
import dev.irohaco.core.application.RemoteAnswering
import dev.irohaco.core.application.RemoteEmbedding
import dev.irohaco.core.application.ManageEgress.UrlKind
import dev.irohaco.core.contracts.EgressGrant
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.GenerationParameter
import dev.irohaco.core.contracts.ModelCapability
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteModel
import dev.irohaco.core.contracts.RemoteProtocol
import dev.irohaco.core.contracts.TaskType
import dev.irohaco.core.designsystem.component.label
import dev.irohaco.core.designsystem.component.remoteFailureText
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
    models: List<RemoteModel> = emptyList(),
    onAddModel: (connectionId: String, modelId: String, name: String) -> Unit = { _, _, _ -> },
    onRemoveModel: (String) -> Unit = {},
    modelTest: SettingsViewModel.ModelTest? = null,
    onDismissModelTest: () -> Unit = {},
    /** The last connection could not be added. */
    connectionFailed: Boolean = false,
    onDismissConnectionFailed: () -> Unit = {},
    /** Names a connection; a blank name goes back to the suggested one. */
    onRename: (id: String, name: String) -> Unit = { _, _ -> },
    onSetParameters: (profileId: String, values: Map<String, String>) -> Unit = { _, _ -> },
    optionsLookup: SettingsViewModel.OptionsLookup? = null,
    onLookUpOptions: (RemoteModel) -> Unit = {},
    onDismissOptions: () -> Unit = {},
) {
    var tuning by remember { mutableStateOf<Pair<RemoteModel, RemoteProtocol>?>(null) }
    tuning?.let { (model, protocol) ->
        ParametersDialog(
            model = model,
            parameters = GenerationParameter.of(protocol),
            lookup = optionsLookup,
            onLookUp = { onLookUpOptions(model) },
            onDismiss = { tuning = null; onDismissOptions() },
            onSave = { tuning = null; onDismissOptions(); onSetParameters(model.profileId, it) },
        )
    }
    var renaming by remember { mutableStateOf<RemoteConnection?>(null) }
    renaming?.let { connection ->
        var name by rememberSaveable { mutableStateOf(connection.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.settings_remote_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_remote_name)) },
                    placeholder = { Text(RemoteConnection.suggestedName(connection.baseUrl)) },
                    supportingText = { Text(stringResource(R.string.settings_remote_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = { TextButton(onClick = { renaming = null; onRename(connection.id, name) }) { Text(stringResource(R.string.settings_remote_model_done)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.settings_ai_cancel)) } },
        )
    }
    if (connectionFailed) {
        AlertDialog(
            onDismissRequest = onDismissConnectionFailed,
            text = { Text(stringResource(R.string.settings_remote_add_failed)) },
            confirmButton = { TextButton(onClick = onDismissConnectionFailed) { Text(stringResource(R.string.settings_remote_model_done)) } },
        )
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<RemoteConnection?>(null) }
    var addingModelTo by rememberSaveable { mutableStateOf<String?>(null) }
    addingModelTo?.let { connectionId ->
        AddModelDialog(
            test = modelTest,
            onDismiss = {
                addingModelTo = null
                onDismissModelTest()
            },
            onTest = { modelId, name -> onAddModel(connectionId, modelId, name) },
        )
    }
    if (adding) AddConnectionDialog(onDismiss = { adding = false }) { protocol, url, key -> adding = false; onAdd(protocol, url, key) }
    // A model removed takes the index it built with it (ADR-0032), so it asks first.
    var removingModel by remember { mutableStateOf<RemoteModel?>(null) }
    removingModel?.let { model ->
        AlertDialog(
            onDismissRequest = { removingModel = null },
            title = { Text(stringResource(R.string.settings_remote_model_remove_title, model.displayName)) },
            text = { Text(stringResource(R.string.settings_remote_model_remove_body)) },
            confirmButton = {
                TextButton(onClick = { removingModel = null; onRemoveModel(model.profileId) }) { Text(stringResource(R.string.settings_ai_delete)) }
            },
            dismissButton = { TextButton(onClick = { removingModel = null }) { Text(stringResource(R.string.settings_ai_cancel)) } },
        )
    }
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
            supportingContent = {
                // Every send to a plain-http address goes out unencrypted, so the list keeps saying so (PRD §10).
                val plain = ManageEgress.urlKind(connection.baseUrl) == UrlKind.PLAIN_HTTP
                val address = if (plain) stringResource(R.string.settings_remote_http_label, connection.baseUrl) else connection.baseUrl
                Text("${connection.protocol.displayName} · $address", color = if (plain) MaterialTheme.colorScheme.error else Color.Unspecified)
            },
            trailingContent = {
                Row {
                    IconButton(onClick = { renaming = connection }) {
                        Icon(painterResource(IrohacoIcons.Edit), stringResource(R.string.settings_remote_rename))
                    }
                    IconButton(onClick = { removing = connection }) {
                        Icon(painterResource(IrohacoIcons.Delete), stringResource(R.string.settings_remote_remove))
                    }
                }
            },
        ) { Text(connection.serviceName, style = MaterialTheme.typography.titleMedium) }
        models.filter { it.connectionId == connection.id }.forEach { model ->
            ListItem(
                supportingContent = {
                    Text(
                        listOf(capabilitiesText(model.capabilities)).plus(model.parameters.map { (path, value) -> "$path: $value" }).joinToString("\n"),
                    )
                },
                trailingContent = {
                    Row {
                        if (GenerationParameter.of(connection.protocol).isNotEmpty()) {
                            IconButton(onClick = { tuning = model to connection.protocol }) {
                                Icon(painterResource(IrohacoIcons.Settings), stringResource(R.string.settings_remote_parameters))
                            }
                        }
                        IconButton(onClick = { removingModel = model }) {
                            Icon(painterResource(IrohacoIcons.Delete), stringResource(R.string.settings_remote_model_remove))
                        }
                    }
                },
            ) { Text(model.displayName) }
        }
        ListItem(
            leadingContent = { Icon(painterResource(IrohacoIcons.Add), contentDescription = null) },
            modifier = Modifier.selectable(selected = false, role = Role.Button) { addingModelTo = connection.id },
        ) { Text(stringResource(R.string.settings_remote_model_add)) }
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
                ) { Text(stringResource(payload.label())) }
            }
        }
    }
    ListItem(
        leadingContent = { Icon(painterResource(IrohacoIcons.Add), contentDescription = null) },
        modifier = Modifier.selectable(selected = false, role = Role.Button) { adding = true },
    ) { Text(stringResource(R.string.settings_remote_add)) }
}

/** What the connection test found, in a line. */
@Composable
private fun capabilitiesText(capabilities: Set<ModelCapability>) = listOfNotNull(
    stringResource(R.string.settings_remote_cap_image).takeIf { ModelCapability.INPUT_IMAGE in capabilities },
    stringResource(R.string.settings_remote_cap_streaming).takeIf { ModelCapability.STREAMING in capabilities },
    stringResource(R.string.settings_remote_cap_embedding).takeIf { ModelCapability.OUTPUT_EMBEDDING in capabilities },
).ifEmpty { listOf(stringResource(R.string.settings_remote_cap_text_only)) }.joinToString(" · ")

/**
 * A model ID and a name; adding runs the connection test, which sends the app's sample text and image
 * to the model, never the user's photos (ADR-0032), and keeps only what it confirmed.
 */
@Composable
private fun AddModelDialog(test: SettingsViewModel.ModelTest?, onDismiss: () -> Unit, onTest: (modelId: String, name: String) -> Unit) {
    var modelId by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val running = test is SettingsViewModel.ModelTest.Running
    val editable = test == null || test is SettingsViewModel.ModelTest.Failed
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.settings_remote_model_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text(stringResource(R.string.settings_remote_model_id)) },
                    singleLine = true,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_remote_model_name)) },
                    singleLine = true,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (test) {
                    null -> Text(stringResource(R.string.settings_remote_model_test_hint), style = MaterialTheme.typography.bodySmall)
                    SettingsViewModel.ModelTest.Running -> {
                        Text(stringResource(R.string.settings_remote_model_testing), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is SettingsViewModel.ModelTest.Added -> {
                        Text(stringResource(R.string.settings_remote_model_added, capabilitiesText(test.capabilities)))
                        if (ModelCapability.OUTPUT_EMBEDDING in test.capabilities) {
                            if (!test.capabilities.containsAll(RemoteEmbedding.NEEDED)) {
                                Text(stringResource(R.string.settings_remote_model_cannot_index), style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (!test.capabilities.containsAll(RemoteAnswering.NEEDED)) {
                            Text(stringResource(R.string.settings_remote_model_cannot_answer), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is SettingsViewModel.ModelTest.Failed -> Text(
                        remoteFailureText(test.cause) ?: stringResource(R.string.settings_remote_model_test_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (test is SettingsViewModel.ModelTest.Added) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_remote_model_done)) }
            } else {
                TextButton(enabled = modelId.isNotBlank() && !running, onClick = { onTest(modelId, name) }) {
                    Text(stringResource(R.string.settings_remote_model_test))
                }
            }
        },
        dismissButton = {
            if (test !is SettingsViewModel.ModelTest.Added) {
                TextButton(enabled = !running, onClick = onDismiss) { Text(stringResource(R.string.settings_ai_cancel)) }
            }
        },
    )
}

/**
 * The request fields of [parameters], named as the API names them; an empty field is not sent, so the model's own
 * default applies. models.dev, read only when the user asks, fills in what this model takes.
 */
@Composable
private fun ParametersDialog(
    model: RemoteModel,
    parameters: List<GenerationParameter>,
    lookup: SettingsViewModel.OptionsLookup?,
    onLookUp: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    var values by remember { mutableStateOf(model.parameters) }
    val options = (lookup as? SettingsViewModel.OptionsLookup.Found)?.options
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_remote_parameters_title, model.displayName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                Text(stringResource(R.string.settings_remote_parameters_hint), style = MaterialTheme.typography.bodySmall)
                parameters.forEach { parameter ->
                    val effort = parameter.path.endsWith("effort")
                    val choices = if (effort) options?.efforts ?: parameter.values else parameter.values
                    val hint = when {
                        effort && options?.efforts != null -> stringResource(R.string.settings_remote_parameters_listed, options.efforts!!.joinToString(", "))
                        parameter.path == "thinking.budget_tokens" && options?.minBudget != null ->
                            stringResource(R.string.settings_remote_parameters_min, options.minBudget!!)
                        parameter.path.startsWith("max_") && options?.maxOutput != null ->
                            stringResource(R.string.settings_remote_parameters_max, options.maxOutput!!)
                        parameter.path == "max_tokens" -> stringResource(R.string.settings_remote_parameters_default, 1024)
                        else -> null
                    }
                    ParameterField(parameter, values[parameter.path].orEmpty(), choices, hint) { value ->
                        values = if (value.isBlank()) values - parameter.path else values + (parameter.path to value)
                    }
                }
                when (lookup) {
                    null -> TextButton(onClick = onLookUp) { Text(stringResource(R.string.settings_remote_parameters_look_up)) }
                    SettingsViewModel.OptionsLookup.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is SettingsViewModel.OptionsLookup.Found -> if (lookup.options == null) {
                        Text(stringResource(R.string.settings_remote_parameters_unlisted), style = MaterialTheme.typography.bodySmall)
                    }
                    is SettingsViewModel.OptionsLookup.Failed -> Text(
                        remoteFailureText(lookup.cause) ?: stringResource(R.string.settings_remote_parameters_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(stringResource(R.string.settings_remote_parameters_look_up_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(values) }) { Text(stringResource(R.string.settings_remote_parameters_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_ai_cancel)) } },
    )
}

/** A field for one request field: numbers only for a number, otherwise any text, with the documented values to pick from. */
@Composable
private fun ParameterField(parameter: GenerationParameter, value: String, choices: List<String>, hint: String?, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(if (parameter.number) it.filter(Char::isDigit) else it) },
        label = { Text(parameter.path) },
        placeholder = { Text(stringResource(R.string.settings_remote_parameters_unset)) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = if (parameter.number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        trailingIcon = if (choices.isEmpty()) null else {
            {
                IconButton(onClick = { open = true }) {
                    Icon(painterResource(IrohacoIcons.ExpandMore), stringResource(R.string.settings_remote_parameters_choose))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_remote_parameters_unset)) }, onClick = { open = false; onChange("") })
                    choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { open = false; onChange(choice) }) }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
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
                    models = listOf(RemoteModel("p", "openai", "model-x", "Model X", RemoteAnswering.NEEDED + ModelCapability.STREAMING, parameters = mapOf("reasoning.effort" to "high"))),
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
