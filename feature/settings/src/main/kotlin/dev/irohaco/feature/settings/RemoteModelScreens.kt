package dev.irohaco.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.irohaco.core.application.ManageEgress
import dev.irohaco.core.application.ManageEgress.UrlKind
import dev.irohaco.core.application.RemoteAnswering
import dev.irohaco.core.application.RemoteEmbedding
import dev.irohaco.core.contracts.GenerationParameter
import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import dev.irohaco.core.contracts.ListedModel
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.ModelInstallState
import dev.irohaco.core.contracts.ModelCapability
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteModel
import dev.irohaco.core.contracts.RemoteProtocol
import dev.irohaco.core.designsystem.component.remoteFailureText
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

/**
 * A page over the settings, as Material's full-screen dialog: a bar with close or back and the title, the content
 * scrolling under it, and [bottom]'s buttons kept in reach above the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullScreenPage(
    title: String,
    onBack: () -> Unit,
    closes: Boolean,
    bottom: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PageBody(title, onBack, closes, bottom, content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageBody(title: String, onBack: () -> Unit, closes: Boolean, bottom: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(if (closes) IrohacoIcons.Close else IrohacoIcons.ArrowBack),
                            stringResource(if (closes) R.string.settings_remote_model_done else R.string.settings_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.0.dp) {
                Row(Modifier.fillMaxWidth().padding(IrohacoSpacing.l), horizontalArrangement = Arrangement.End) { bottom() }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.m),
            verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m),
            content = content,
        )
    }
}

/**
 * Adds a remote model for [role] in three steps, one on screen at a time: the service, the model, picked from the
 * service's list and models.dev or typed, and the test, which sends the app's sample text and image, never the
 * user's photos (ADR-0032). Back goes to the step before; what the job will send is said before the test.
 */
@Composable
internal fun AddRemoteModelScreen(
    role: RemoteRole,
    connections: List<RemoteConnection>,
    test: SettingsViewModel.ModelTest?,
    list: SettingsViewModel.ModelList?,
    onList: (connectionId: String?, NewConnection?, endpoint: String?) -> Unit,
    onAdd: (connectionId: String?, NewConnection?, modelId: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var chosen by rememberSaveable { mutableStateOf(connections.firstOrNull()?.id) }
    var protocol by rememberSaveable { mutableStateOf(role.protocols.first()) }
    var url by rememberSaveable { mutableStateOf(protocol.defaultBaseUrl) }
    // Not saved: the key stays out of the saved instance state.
    var key by remember { mutableStateOf("") }
    var service by rememberSaveable { mutableStateOf("") }
    var plainAccepted by rememberSaveable { mutableStateOf(false) }
    var modelId by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf("") }
    val running = test is SettingsViewModel.ModelTest.Running
    val added = test is SettingsViewModel.ModelTest.Added
    val kind = ManageEgress.urlKind(url)
    val connectionReady = chosen != null || key.isNotBlank() && (kind == UrlKind.HTTPS || kind == UrlKind.PLAIN_HTTP && plainAccepted)
    val newConnection = { if (chosen == null) NewConnection(protocol, url, key, service) else null }
    val load = { onList(chosen, newConnection(), endpoint.takeIf { it.isNotBlank() }) }
    // The candidates are read as the model step opens, so the user picks rather than types.
    LaunchedEffect(step) { if (step == 1) load() }
    val back = {
        when {
            running -> Unit
            step == 0 || added -> onDismiss()
            else -> step -= 1
        }
    }
    FullScreenPage(
        title = stringResource(if (role == RemoteRole.ANSWER) R.string.settings_ai_add_remote_answer else R.string.settings_ai_add_remote_index),
        onBack = back,
        closes = step == 0 || added,
        bottom = {
            when {
                added -> Button(onClick = onDismiss) { Text(stringResource(R.string.settings_remote_model_done)) }
                step == 0 -> Button(enabled = connectionReady, onClick = { step = 1 }) { Text(stringResource(R.string.settings_ai_remote_next)) }
                step == 1 -> Button(enabled = modelId.isNotBlank(), onClick = { step = 2 }) { Text(stringResource(R.string.settings_ai_remote_next)) }
                else -> Button(enabled = !running, onClick = { onAdd(chosen, newConnection(), modelId, name) }) {
                    Text(stringResource(R.string.settings_ai_remote_test_and_use))
                }
            }
        },
    ) {
        val steps = listOf(R.string.settings_ai_remote_connection, R.string.settings_ai_remote_model, R.string.settings_ai_remote_step_test)
        Text(
            stringResource(R.string.settings_ai_remote_step, step + 1, steps.size, stringResource(steps[step])),
            style = MaterialTheme.typography.titleMedium,
        )
        when (step) {
            0 -> {
                Column(Modifier.selectableGroup()) {
                    connections.forEach { connection ->
                        ChoiceRow(connection.id == chosen, true, "${connection.serviceName} · ${connection.protocol.displayName}") { chosen = connection.id }
                    }
                    ChoiceRow(chosen == null, true, stringResource(R.string.settings_ai_remote_new_connection)) { chosen = null }
                }
                if (chosen == null) {
                    Text(stringResource(R.string.settings_remote_protocol), style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.selectableGroup()) {
                        role.protocols.forEach { entry ->
                            ChoiceRow(entry == protocol, true, entry.displayName) { protocol = entry; url = entry.defaultBaseUrl }
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
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
                    OutlinedTextField(
                        value = service,
                        onValueChange = { service = it },
                        label = { Text(stringResource(R.string.settings_remote_name)) },
                        placeholder = { Text(RemoteConnection.suggestedName(url)) },
                        supportingText = { Text(stringResource(R.string.settings_remote_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            1 -> {
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text(stringResource(R.string.settings_remote_model_id)) },
                    supportingText = { Text(stringResource(R.string.settings_ai_remote_model_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ModelCandidates(list = list, typed = modelId, onReload = load) { listed ->
                    modelId = listed.id
                    if (name.isBlank()) listed.name?.let { name = it }
                }
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text(stringResource(R.string.settings_ai_remote_list_url)) },
                    placeholder = { Text((connections.firstOrNull { it.id == chosen }?.baseUrl ?: url).trim().trimEnd('/') + "/models") },
                    supportingText = { Text(stringResource(R.string.settings_ai_remote_list_url_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_remote_model_name)) },
                    placeholder = { Text(modelId) },
                    singleLine = true,
                    enabled = !running && !added,
                    modifier = Modifier.fillMaxWidth(),
                )
                ListItem(
                    overlineContent = { Text(stringResource(R.string.settings_ai_remote_connection)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(connections.firstOrNull { it.id == chosen }?.serviceName ?: service.ifBlank { RemoteConnection.suggestedName(url) }) }
                ListItem(
                    overlineContent = { Text(stringResource(R.string.settings_remote_model_id)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(modelId) }
                Text(
                    stringResource(if (role == RemoteRole.ANSWER) R.string.settings_ai_remote_sends_answer else R.string.settings_ai_remote_sends_index) +
                        " " + stringResource(R.string.settings_remote_billing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (test) {
                    null -> Text(stringResource(R.string.settings_remote_model_test_hint), style = MaterialTheme.typography.bodySmall)
                    SettingsViewModel.ModelTest.Running -> {
                        Text(stringResource(R.string.settings_remote_model_testing))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is SettingsViewModel.ModelTest.Added -> Text(
                        if (test.usable) {
                            stringResource(R.string.settings_ai_remote_ready)
                        } else {
                            stringResource(if (role == RemoteRole.ANSWER) R.string.settings_remote_model_cannot_answer else R.string.settings_remote_model_cannot_index)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    is SettingsViewModel.ModelTest.Failed -> Text(
                        remoteFailureText(test.cause) ?: stringResource(R.string.settings_remote_model_test_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** The models offered, narrowed to the job by the view model and here to what has been typed; a tap fills the ID in. */
@Composable
private fun ModelCandidates(list: SettingsViewModel.ModelList?, typed: String, onReload: () -> Unit, onPick: (ListedModel) -> Unit) {
    when (list) {
        null, SettingsViewModel.ModelList.Running -> {
            Text(stringResource(R.string.settings_ai_remote_listing), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is SettingsViewModel.ModelList.Failed -> {
            Text(remoteFailureText(list.cause) ?: stringResource(R.string.settings_ai_remote_list_failed), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onReload) { Text(stringResource(R.string.settings_ai_remote_list)) }
        }
        is SettingsViewModel.ModelList.Found -> {
            val shown = list.models.filter { typed.isBlank() || typed.trim().lowercase() in (it.id + " " + it.name.orEmpty()).lowercase() }
            Text(stringResource(R.string.settings_ai_remote_candidates, shown.size), style = MaterialTheme.typography.labelLarge)
            if (shown.isEmpty()) Text(stringResource(R.string.settings_ai_remote_list_none), style = MaterialTheme.typography.bodySmall)
            Column(Modifier.selectableGroup()) {
                shown.take(MAX_CANDIDATES).forEach { model ->
                    ListItem(
                        supportingContent = model.name?.let { { Text(model.id) } },
                        trailingContent = if (model.id == typed.trim()) {
                            { Icon(painterResource(IrohacoIcons.Check), contentDescription = null) }
                        } else {
                            null
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(selected = model.id == typed.trim(), role = Role.RadioButton) { onPick(model) },
                    ) { Text(model.name ?: model.id) }
                }
            }
            if (shown.size > MAX_CANDIDATES) {
                Text(stringResource(R.string.settings_ai_remote_list_more, shown.size - MAX_CANDIDATES), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onReload) { Text(stringResource(R.string.settings_ai_remote_list)) }
        }
    }
}

/** Enough to scroll through; typing narrows the rest down. */
private const val MAX_CANDIDATES = 30

/**
 * One remote model: where it runs, what its test found, its request settings when its API has any, and removing it.
 * The request fields are named as the API names them; an empty one is not sent, so the model's own default applies.
 */
@Composable
internal fun RemoteModelScreen(
    model: RemoteModel,
    connection: RemoteConnection?,
    lookup: SettingsViewModel.OptionsLookup?,
    onLookUp: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
    onMark: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val parameters = connection?.protocol?.let(GenerationParameter::of).orEmpty()
    var values by remember(model.parameters) { mutableStateOf(model.parameters) }
    FullScreenPage(
        title = model.displayName,
        onBack = onDismiss,
        closes = true,
        bottom = {
            if (parameters.isNotEmpty()) {
                Button(enabled = values != model.parameters, onClick = { onSave(values) }) { Text(stringResource(R.string.settings_remote_parameters_save)) }
            }
        },
    ) {
        RemoteModelBody(model, connection, parameters, values, { values = it }, lookup, onLookUp, onMark, onRemove)
    }
}

@Composable
private fun ColumnScope.RemoteModelBody(
    model: RemoteModel,
    connection: RemoteConnection?,
    parameters: List<GenerationParameter>,
    values: Map<String, String>,
    onValues: (Map<String, String>) -> Unit,
    lookup: SettingsViewModel.OptionsLookup?,
    onLookUp: () -> Unit,
    onMark: () -> Unit,
    onRemove: () -> Unit,
) {
    val plain = ListItemDefaults.colors(containerColor = Color.Transparent)
    connection?.let {
        ListItem(
            overlineContent = { Text(stringResource(R.string.settings_ai_remote_connection)) },
            supportingContent = { Text("${it.protocol.displayName} · ${it.baseUrl}") },
            colors = plain,
        ) { Text(it.serviceName) }
    }
    ListItem(overlineContent = { Text(stringResource(R.string.settings_remote_model_id)) }, colors = plain) { Text(model.modelId) }
    ListItem(overlineContent = { Text(stringResource(R.string.settings_remote_detail_can)) }, colors = plain) { Text(capabilitiesText(model.capabilities)) }
    if (parameters.isNotEmpty()) {
        HorizontalDivider()
        Text(stringResource(R.string.settings_remote_parameters), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_remote_parameters_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val options = (lookup as? SettingsViewModel.OptionsLookup.Found)?.options
        parameters.forEach { parameter ->
            val effort = parameter.path.endsWith("effort")
            val choices = if (effort) options?.efforts ?: parameter.values else parameter.values
            val hint = when {
                effort && options?.efforts != null -> stringResource(R.string.settings_remote_parameters_listed, options.efforts!!.joinToString(", "))
                parameter.path == "thinking.budget_tokens" && options?.minBudget != null -> stringResource(R.string.settings_remote_parameters_min, options.minBudget!!)
                parameter.path.startsWith("max_") && options?.maxOutput != null -> stringResource(R.string.settings_remote_parameters_max, options.maxOutput!!)
                parameter.path == "max_tokens" -> stringResource(R.string.settings_remote_parameters_default, 1024)
                else -> null
            }
            ParameterField(parameter, values[parameter.path].orEmpty(), choices, hint) { value ->
                onValues(if (value.isBlank()) values - parameter.path else values + (parameter.path to value))
            }
        }
        when (lookup) {
            null -> OutlinedButton(onClick = onLookUp) { Text(stringResource(R.string.settings_remote_parameters_look_up)) }
            SettingsViewModel.OptionsLookup.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is SettingsViewModel.OptionsLookup.Found -> if (lookup.options == null) {
                Text(stringResource(R.string.settings_remote_parameters_unlisted, model.modelId), style = MaterialTheme.typography.bodySmall)
            }
            is SettingsViewModel.OptionsLookup.Failed -> Text(
                remoteFailureText(lookup.cause) ?: stringResource(R.string.settings_remote_parameters_failed),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(stringResource(R.string.settings_remote_parameters_look_up_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
    OutlinedButton(onClick = onMark) { Text(stringResource(R.string.settings_remote_detail_mark)) }
    TextButton(onClick = onRemove) { Text(stringResource(R.string.settings_remote_model_remove), color = MaterialTheme.colorScheme.error) }
}

/** One model on the device: what it is and where it stands, its mark, and deleting its file. */
@Composable
internal fun LocalModelScreen(model: ModelArtifact, state: ModelInstallState, onMark: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    FullScreenPage(title = model.displayName, onBack = onDismiss, closes = true) {
        val plain = ListItemDefaults.colors(containerColor = Color.Transparent)
        ListItem(overlineContent = { Text(stringResource(R.string.settings_ai_remote_connection)) }, colors = plain) { Text(stringResource(R.string.settings_ai_on_device)) }
        ListItem(overlineContent = { Text(stringResource(R.string.settings_local_state)) }, colors = plain) { Text(modelStateLabel(state)) }
        ListItem(overlineContent = { Text(stringResource(R.string.settings_local_size)) }, colors = plain) { Text(Formatter.formatShortFileSize(context, model.sizeBytes)) }
        HorizontalDivider()
        OutlinedButton(onClick = onMark) { Text(stringResource(R.string.settings_remote_detail_mark)) }
        if (state is ModelInstallState.Installed) {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.settings_ai_delete), color = MaterialTheme.colorScheme.error) }
        }
    }
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
                Box {
                    IconButton(onClick = { open = true }) {
                        Icon(painterResource(IrohacoIcons.ExpandMore), stringResource(R.string.settings_remote_parameters_choose))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.settings_remote_parameters_unset)) }, onClick = { open = false; onChange("") })
                        choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { open = false; onChange(choice) }) }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@IrohacoPreviews
@Composable
private fun RemoteModelScreenPreview() {
    val model = RemoteModel(
        "p", "c", "claude-x", "Claude X", RemoteAnswering.NEEDED + ModelCapability.STREAMING,
        parameters = mapOf("thinking.type" to "adaptive", "output_config.effort" to "high"),
    )
    val connection = RemoteConnection("c", RemoteProtocol.ANTHROPIC_MESSAGES, "https://api.example.com/v1")
    IrohacoTheme(dynamicColor = false) {
        PageBody(model.displayName, {}, true, { Button(onClick = {}) { Text("Save") } }) {
            RemoteModelBody(model, connection, GenerationParameter.of(connection.protocol), model.parameters, {}, null, {}, {}, {})
        }
    }
}
