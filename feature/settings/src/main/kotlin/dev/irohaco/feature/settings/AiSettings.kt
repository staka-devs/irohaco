package dev.irohaco.feature.settings

import dev.irohaco.core.contracts.ListedModel
import androidx.compose.foundation.layout.Row
import dev.irohaco.core.contracts.GenerationParameter
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.irohaco.core.application.ManageEgress
import dev.irohaco.core.application.ManageEgress.UrlKind
import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.GpuCheck
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.ModelInstallState
import dev.irohaco.core.contracts.ModelInstallState.Failed.Reason
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteModel
import dev.irohaco.core.contracts.RemoteProtocol
import dev.irohaco.core.designsystem.component.MarkedModelName
import dev.irohaco.core.designsystem.component.remoteFailureText
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

/**
 * Settings > AI (DESIGN §4.5, M2-02), one card per job: the model that builds the photo index and the
 * one that answers questions. Each card lists the models that can do its job, on the device or remote,
 * and picking one puts it to use; a model on the device is downloaded, paused or deleted on its own
 * line. A remote model is added from the card it is for, connection and test included, and picking it
 * is the consent to send it what that job needs (ADR-0032). The processor and importing a file sit
 * under "Details".
 */
@Composable
internal fun AiSettings(
    models: List<ModelArtifact>,
    states: Map<String, ModelInstallState>,
    importResult: ModelInstallState?,
    onDownload: (String) -> Unit,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
    onPause: (String) -> Unit = {},
    onCancel: (String) -> Unit = {},
    backend: AiBackend? = null,
    gpuCheck: GpuCheck? = null,
    onSelectBackend: (AiBackend) -> Unit = {},
    answerModels: List<ModelArtifact> = emptyList(),
    remoteAnswerModels: List<RemoteModel> = emptyList(),
    answerModel: String? = null,
    onSelectAnswerModel: (String) -> Unit = {},
    remoteEmbeddingModels: List<RemoteModel> = emptyList(),
    /** Null for the model on this device. */
    embeddingModel: String? = null,
    onSelectEmbeddingModel: (String?) -> Unit = {},
    connections: List<RemoteConnection> = emptyList(),
    modelTest: SettingsViewModel.ModelTest? = null,
    onAddRemote: (RemoteRole, connectionId: String?, NewConnection?, modelId: String, name: String) -> Unit = { _, _, _, _, _ -> },
    onDismissModelTest: () -> Unit = {},
    onRemoveRemote: (String) -> Unit = {},
    onMarkColor: (id: String, color: Long?) -> Unit = { _, _ -> },
    onMarkImage: (id: String, uri: String?) -> Unit = { _, _ -> },
    onSetParameters: (profileId: String, values: Map<String, String>) -> Unit = { _, _ -> },
    optionsLookup: SettingsViewModel.OptionsLookup? = null,
    onLookUpOptions: (RemoteModel) -> Unit = {},
    onDismissOptions: () -> Unit = {},
    modelList: SettingsViewModel.ModelList? = null,
    onListModels: (RemoteRole, connectionId: String?, NewConnection?, endpoint: String?) -> Unit = { _, _, _, _ -> },
    onDismissModelList: () -> Unit = {},
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
    var marking by remember { mutableStateOf<Pair<String, String>?>(null) }
    marking?.let { (id, name) ->
        MarkDialog(id, name, onColor = { onMarkColor(id, it) }, onImage = { onMarkImage(id, it) }) { marking = null }
    }
    var deleting by remember { mutableStateOf<ModelArtifact?>(null) }
    deleting?.let { model ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.settings_ai_delete_title, model.displayName)) },
            text = { Text(stringResource(R.string.settings_ai_delete_body)) },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(model.id) }) { Text(stringResource(R.string.settings_ai_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_ai_cancel)) } },
        )
    }
    var removing by remember { mutableStateOf<RemoteModel?>(null) }
    removing?.let { model ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.settings_remote_model_remove_title, model.displayName)) },
            text = { Text(stringResource(R.string.settings_remote_model_remove_body)) },
            confirmButton = { TextButton(onClick = { removing = null; onRemoveRemote(model.profileId) }) { Text(stringResource(R.string.settings_ai_delete)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.settings_ai_cancel)) } },
        )
    }
    var adding by rememberSaveable { mutableStateOf<RemoteRole?>(null) }
    adding?.let { role ->
        AddRemoteModelDialog(
            role = role,
            connections = connections.filter { it.protocol in role.protocols },
            test = modelTest,
            onAdd = { connectionId, new, modelId, name -> onAddRemote(role, connectionId, new, modelId, name) },
            onDismiss = { adding = null; onDismissModelTest(); onDismissModelList() },
            list = modelList,
            onList = { connectionId, new, endpoint -> onListModels(role, connectionId, new, endpoint) },
        )
    }
    val hosts = connections.associate { it.id to it.serviceName }
    val answerIds = answerModels.map { it.id }.toSet()
    val local = LocalModels(states, onDownload, onPause, onCancel, onDelete = { deleting = it }, onMark = { marking = it.id to it.displayName })
    Column(Modifier.padding(horizontal = IrohacoSpacing.l), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m)) {
        RoleCard(R.string.settings_ai_index_card, R.string.settings_ai_index_card_hint, onAdd = { adding = RemoteRole.INDEX }) {
            models.filter { it.id !in answerIds }.forEach { model ->
                local.Row(model, selected = embeddingModel == null && states[model.id] is ModelInstallState.Installed) { onSelectEmbeddingModel(null) }
            }
            remoteEmbeddingModels.forEach { model ->
                RemoteRow(model, hosts[model.connectionId], selected = model.profileId == embeddingModel, onSelect = { onSelectEmbeddingModel(model.profileId) }, onMark = { marking = model.profileId to model.displayName }) { removing = model }
            }
        }
        RoleCard(R.string.settings_ai_answer_card, R.string.settings_ai_answer_card_hint, onAdd = { adding = RemoteRole.ANSWER }) {
            answerModels.forEach { model -> local.Row(model, selected = model.id == answerModel) { onSelectAnswerModel(model.id) } }
            remoteAnswerModels.forEach { model ->
                val protocol = connections.firstOrNull { it.id == model.connectionId }?.protocol
                RemoteRow(
                    model, hosts[model.connectionId], selected = model.profileId == answerModel, onSelect = { onSelectAnswerModel(model.profileId) },
                    onMark = { marking = model.profileId to model.displayName },
                    onTune = protocol?.takeIf { GenerationParameter.of(it).isNotEmpty() }?.let { { tuning = model to it } },
                ) { removing = model }
            }
        }
        Details(importResult, onImport, backend, gpuCheck, onSelectBackend)
    }
}

/** The protocols that can do a job: chat formats answer, embedding formats index (RemoteAnswerSession, RemoteEmbeddingSession). */
internal val RemoteRole.protocols: Set<RemoteProtocol>
    get() = when (this) {
        RemoteRole.ANSWER -> setOf(RemoteProtocol.OPENAI_CHAT, RemoteProtocol.OPENAI_RESPONSES, RemoteProtocol.ANTHROPIC_MESSAGES)
        RemoteRole.INDEX -> setOf(RemoteProtocol.OPENAI_EMBEDDINGS, RemoteProtocol.GEMINI_EMBED_CONTENT)
    }

@Composable
private fun RoleCard(title: Int, hint: Int, onAdd: () -> Unit, rows: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = IrohacoSpacing.s)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = IrohacoSpacing.l))
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = IrohacoSpacing.l),
            )
            Column(Modifier.selectableGroup()) { rows() }
            ListItem(
                leadingContent = { Icon(painterResource(IrohacoIcons.Add), contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.selectable(selected = false, role = Role.Button, onClick = onAdd),
            ) { Text(stringResource(R.string.settings_ai_add_remote), color = MaterialTheme.colorScheme.primary) }
        }
    }
}

/** A model on the device: picking needs it installed, and its line downloads, pauses, resumes or deletes it. */
private class LocalModels(
    val states: Map<String, ModelInstallState>,
    val onDownload: (String) -> Unit,
    val onPause: (String) -> Unit,
    val onCancel: (String) -> Unit,
    val onDelete: (ModelArtifact) -> Unit,
    val onMark: (ModelArtifact) -> Unit,
) {
    @Composable
    fun Row(model: ModelArtifact, selected: Boolean, onSelect: () -> Unit) {
        val state = states[model.id] ?: ModelInstallState.NotInstalled
        val installed = state is ModelInstallState.Installed
        val context = LocalContext.current
        ListItem(
            leadingContent = { RadioButton(selected = selected, onClick = null, enabled = installed) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
                    Text(stringResource(R.string.settings_ai_on_device) + " · " + modelStateLabel(state) + " · " + Formatter.formatShortFileSize(context, model.sizeBytes))
                    val bytes = when (state) {
                        is ModelInstallState.Downloading -> state.downloadedBytes to state.totalBytes
                        is ModelInstallState.Paused -> state.downloadedBytes to state.totalBytes
                        else -> null
                    }
                    bytes?.let { (done, total) ->
                        LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            trailingContent = {
                when (state) {
                    is ModelInstallState.Installed -> TextButton(onClick = { onDelete(model) }) { Text(stringResource(R.string.settings_ai_delete)) }
                    is ModelInstallState.Downloading -> Column {
                        TextButton(onClick = { onPause(model.id) }) { Text(stringResource(R.string.settings_ai_pause)) }
                        TextButton(onClick = { onCancel(model.id) }) { Text(stringResource(R.string.settings_ai_cancel)) }
                    }
                    is ModelInstallState.Paused -> Column {
                        TextButton(onClick = { onDownload(model.id) }) { Text(stringResource(R.string.settings_ai_resume)) }
                        TextButton(onClick = { onCancel(model.id) }) { Text(stringResource(R.string.settings_ai_cancel)) }
                    }
                    ModelInstallState.Verifying -> Unit
                    // A failed download keeps what it got, and the retry resumes from there.
                    is ModelInstallState.Failed -> TextButton(onClick = { onDownload(model.id) }) { Text(stringResource(R.string.settings_ai_retry)) }
                    ModelInstallState.NotInstalled -> TextButton(onClick = { onDownload(model.id) }) { Text(stringResource(R.string.settings_ai_download)) }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.selectable(selected = selected, enabled = installed, role = Role.RadioButton, onClick = onSelect),
        ) { MarkedModelName(model.displayName, model.id) { onMark(model) } }
    }
}

@Composable
private fun RemoteRow(
    model: RemoteModel,
    host: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    onMark: () -> Unit,
    /** Opens the model's request settings; null where its API has none. */
    onTune: (() -> Unit)? = null,
    onRemove: () -> Unit,
) {
    ListItem(
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        supportingContent = {
            // What the user set goes with every question, so the row says it.
            Text((listOf(stringResource(R.string.settings_ai_remote_at, host.orEmpty())) + model.parameters.map { (path, value) -> "$path: $value" }).joinToString("\n"))
        },
        trailingContent = {
            Row {
                onTune?.let { IconButton(onClick = it) { Icon(painterResource(IrohacoIcons.Settings), stringResource(R.string.settings_remote_parameters)) } }
                IconButton(onClick = onRemove) { Icon(painterResource(IrohacoIcons.Delete), stringResource(R.string.settings_remote_model_remove)) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) { MarkedModelName(model.displayName, model.profileId, onMark) }
}

/** The fills a mark can take; strong enough to read a white or black letter on. */
private val MARK_COLORS = listOf(0xFF1A73E8, 0xFF0F9D58, 0xFFF4B400, 0xFFDB4437, 0xFF8E24AA, 0xFF000000, 0xFFD97757, 0xFF10A37F)

/** A color for the mark, or a picture such as the service's logo in place of the letter. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkDialog(id: String, name: String, onColor: (Long?) -> Unit, onImage: (String?) -> Unit, onDismiss: () -> Unit) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { onImage(it.toString()) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { MarkedModelName(name, id) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m)) {
                Text(stringResource(R.string.settings_ai_mark_color), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                    MARK_COLORS.forEachIndexed { i, color ->
                        val label = stringResource(R.string.settings_ai_mark_color_n, i + 1)
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(Color(color))
                                .clickable(onClickLabel = label, role = Role.Button) { onColor(color) }
                                .semantics { contentDescription = label },
                        )
                    }
                }
                TextButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(stringResource(R.string.settings_ai_mark_image))
                }
                Text(stringResource(R.string.settings_ai_mark_image_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_ai_mark_done)) } },
        dismissButton = {
            TextButton(onClick = { onColor(null); onImage(null) }) { Text(stringResource(R.string.settings_ai_mark_reset)) }
        },
    )
}

/** The processor and importing a model file: rarely touched, so folded away. */
@Composable
private fun Details(importResult: ModelInstallState?, onImport: () -> Unit, backend: AiBackend?, gpuCheck: GpuCheck?, onSelectBackend: (AiBackend) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ListItem(
        supportingContent = { Text(stringResource(R.string.settings_ai_details_summary)) },
        trailingContent = { Icon(painterResource(IrohacoIcons.ExpandMore), contentDescription = null, modifier = Modifier.rotate(if (open) 180f else 0f)) },
        modifier = Modifier.toggleable(open, role = Role.Button) { open = it },
    ) { Text(stringResource(R.string.settings_ai_details)) }
    if (!open) return
    SectionTitle(R.string.settings_ai_backend)
    val gpuFails = gpuCheck?.works == false
    val running = if (gpuFails) AiBackend.CPU else backend ?: AiBackend.GPU
    Column(Modifier.selectableGroup()) {
        BackendRow(
            R.string.settings_ai_backend_gpu,
            running == AiBackend.GPU,
            enabled = !gpuFails,
            note = when {
                gpuFails -> R.string.settings_ai_backend_gpu_fails
                gpuCheck == null -> R.string.settings_ai_backend_gpu_unchecked
                else -> null
            },
        ) { onSelectBackend(AiBackend.GPU) }
        BackendRow(R.string.settings_ai_backend_cpu, running == AiBackend.CPU, enabled = true, note = null) { onSelectBackend(AiBackend.CPU) }
    }
    ListItem(
        supportingContent = {
            Text(
                when (importResult) {
                    is ModelInstallState.Failed -> modelStateLabel(importResult)
                    is ModelInstallState.Installed -> stringResource(R.string.settings_ai_import_done)
                    else -> stringResource(R.string.settings_ai_import_summary)
                },
            )
        },
        trailingContent = { TextButton(onClick = onImport) { Text(stringResource(R.string.settings_ai_import_choose)) } },
    ) { Text(stringResource(R.string.settings_ai_import)) }
}

/**
 * Adds a remote model for [role] in one dialog: a connection already added or a new one, the model ID,
 * then the test, which sends the app's sample text and image, never the user's photos (ADR-0032).
 * What the job will send is said once, plainly, before the button.
 */
@Composable
private fun AddRemoteModelDialog(
    role: RemoteRole,
    connections: List<RemoteConnection>,
    test: SettingsViewModel.ModelTest?,
    onAdd: (connectionId: String?, NewConnection?, modelId: String, name: String) -> Unit,
    onDismiss: () -> Unit,
    list: SettingsViewModel.ModelList? = null,
    onList: (connectionId: String?, NewConnection?, endpoint: String?) -> Unit = { _, _, _ -> },
) {
    var chosen by rememberSaveable { mutableStateOf(connections.firstOrNull()?.id) }
    var protocol by rememberSaveable { mutableStateOf(role.protocols.first()) }
    var url by rememberSaveable { mutableStateOf(protocol.defaultBaseUrl) }
    // Not saved: the key stays out of the saved instance state.
    var key by remember { mutableStateOf("") }
    var service by rememberSaveable { mutableStateOf("") }
    var plainAccepted by rememberSaveable { mutableStateOf(false) }
    var modelId by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val running = test is SettingsViewModel.ModelTest.Running
    val editable = test == null || test is SettingsViewModel.ModelTest.Failed
    val kind = ManageEgress.urlKind(url)
    val connectionReady = chosen != null || key.isNotBlank() && (kind == UrlKind.HTTPS || kind == UrlKind.PLAIN_HTTP && plainAccepted)
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(if (role == RemoteRole.ANSWER) R.string.settings_ai_add_remote_answer else R.string.settings_ai_add_remote_index)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                Text(stringResource(R.string.settings_ai_remote_connection), style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) {
                    connections.forEach { connection ->
                        ChoiceRow(connection.id == chosen, editable, "${connection.serviceName} · ${connection.protocol.displayName}") { chosen = connection.id }
                    }
                    ChoiceRow(chosen == null, editable, stringResource(R.string.settings_ai_remote_new_connection)) { chosen = null }
                }
                if (chosen == null) {
                    Column(Modifier.selectableGroup()) {
                        role.protocols.forEach { entry ->
                            ChoiceRow(entry == protocol, editable, entry.displayName) { protocol = entry; url = entry.defaultBaseUrl }
                        }
                    }
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; plainAccepted = false },
                        label = { Text(stringResource(R.string.settings_remote_url)) },
                        isError = kind == UrlKind.INVALID,
                        singleLine = true,
                        enabled = editable,
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
                        value = service,
                        onValueChange = { service = it },
                        label = { Text(stringResource(R.string.settings_remote_name)) },
                        placeholder = { Text(RemoteConnection.suggestedName(url)) },
                        supportingText = { Text(stringResource(R.string.settings_remote_name_hint)) },
                        singleLine = true,
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text(stringResource(R.string.settings_remote_key)) },
                        singleLine = true,
                        enabled = editable,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(stringResource(R.string.settings_ai_remote_model), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text(stringResource(R.string.settings_remote_model_id)) },
                    singleLine = true,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
                ModelCandidates(
                    list = list,
                    enabled = editable,
                    base = connections.firstOrNull { it.id == chosen }?.baseUrl ?: url,
                    typed = modelId,
                    onList = { endpoint -> onList(chosen, if (chosen == null) NewConnection(protocol, url, key, service) else null, endpoint) },
                ) { listed ->
                    modelId = listed.id
                    if (name.isBlank()) listed.name?.let { name = it }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_remote_model_name)) },
                    singleLine = true,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(if (role == RemoteRole.ANSWER) R.string.settings_ai_remote_sends_answer else R.string.settings_ai_remote_sends_index) +
                        " " + stringResource(R.string.settings_remote_billing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (test) {
                    null -> Unit
                    SettingsViewModel.ModelTest.Running -> {
                        Text(stringResource(R.string.settings_remote_model_testing), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is SettingsViewModel.ModelTest.Added -> Text(
                        if (test.usable) {
                            stringResource(R.string.settings_ai_remote_ready)
                        } else {
                            stringResource(if (role == RemoteRole.ANSWER) R.string.settings_remote_model_cannot_answer else R.string.settings_remote_model_cannot_index)
                        },
                    )
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
                TextButton(enabled = modelId.isNotBlank() && connectionReady && !running, onClick = {
                    onAdd(chosen, if (chosen == null) NewConnection(protocol, url, key, service) else null, modelId, name)
                }) { Text(stringResource(R.string.settings_ai_remote_test_and_use)) }
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
 * Model IDs to pick from: the service's own list (at [base]/models, or another address for a proxy) and models.dev,
 * read when the user asks, narrowed to the job and to what has been typed.
 */
@Composable
private fun ModelCandidates(
    list: SettingsViewModel.ModelList?,
    enabled: Boolean,
    base: String,
    typed: String,
    onList: (endpoint: String?) -> Unit,
    onPick: (ListedModel) -> Unit,
) {
    var endpoint by rememberSaveable { mutableStateOf("") }
    var other by rememberSaveable { mutableStateOf(false) }
    if (other) {
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = { Text(stringResource(R.string.settings_ai_remote_list_url)) },
            placeholder = { Text(base.trim().trimEnd('/') + "/models") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row {
        TextButton(enabled = enabled && list != SettingsViewModel.ModelList.Running, onClick = { onList(endpoint.takeIf { other && it.isNotBlank() }) }) {
            Text(stringResource(R.string.settings_ai_remote_list))
        }
        if (!other) TextButton(enabled = enabled, onClick = { other = true }) { Text(stringResource(R.string.settings_ai_remote_list_other)) }
    }
    when (list) {
        null -> Unit
        SettingsViewModel.ModelList.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is SettingsViewModel.ModelList.Failed -> Text(
            remoteFailureText(list.cause) ?: stringResource(R.string.settings_ai_remote_list_failed),
            color = MaterialTheme.colorScheme.error,
        )
        is SettingsViewModel.ModelList.Found -> {
            val shown = list.models.filter { typed.isBlank() || typed.trim().lowercase() in (it.id + " " + it.name.orEmpty()).lowercase() }
            if (shown.isEmpty()) Text(stringResource(R.string.settings_ai_remote_list_none), style = MaterialTheme.typography.bodySmall)
            shown.take(MAX_CANDIDATES).forEach { model ->
                ListItem(
                    supportingContent = model.name?.let { { Text(model.id) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.selectable(selected = model.id == typed, enabled = enabled, role = Role.Button) { onPick(model) },
                ) { Text(model.name ?: model.id) }
            }
            if (shown.size > MAX_CANDIDATES) {
                Text(stringResource(R.string.settings_ai_remote_list_more, shown.size - MAX_CANDIDATES), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Enough to scroll through in a dialog; typing narrows the rest down. */
private const val MAX_CANDIDATES = 20

@Composable
private fun ChoiceRow(selected: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) { Text(label) }
}

@Composable
private fun BackendRow(label: Int, selected: Boolean, enabled: Boolean, note: Int?, onClick: () -> Unit) {
    ListItem(
        supportingContent = note?.let { { Text(stringResource(it)) } },
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) { Text(stringResource(label)) }
}

/** One line for the category list: the default model's state. */
@Composable
internal fun modelStateLabel(state: ModelInstallState): String = when (state) {
    ModelInstallState.NotInstalled -> stringResource(R.string.settings_ai_not_installed)
    is ModelInstallState.Downloading -> stringResource(
        R.string.settings_ai_downloading,
        if (state.totalBytes > 0) (100 * state.downloadedBytes / state.totalBytes).toInt() else 0,
    )
    is ModelInstallState.Paused -> stringResource(
        R.string.settings_ai_paused,
        if (state.totalBytes > 0) (100 * state.downloadedBytes / state.totalBytes).toInt() else 0,
    )
    ModelInstallState.Verifying -> stringResource(R.string.settings_ai_verifying)
    is ModelInstallState.Installed -> stringResource(R.string.settings_ai_installed)
    is ModelInstallState.Failed -> stringResource(
        when (state.reason) {
            Reason.DOWNLOAD -> R.string.settings_ai_failed_download
            Reason.DIGEST_MISMATCH -> R.string.settings_ai_failed_digest
            Reason.UNKNOWN_MODEL -> R.string.settings_ai_failed_unknown
            Reason.STORAGE -> R.string.settings_ai_failed_storage
        },
    )
}

private val previewEmbedding = ModelArtifact("e", "EmbeddingGemma 2 Text-Vision 440M", "https://example.invalid", 387_710_976, "x")
private val previewAnswers = listOf(
    ModelArtifact("a1", "Gemma 4 E2B", "https://example.invalid", 2_600_000_000, "x"),
    ModelArtifact("a2", "LFM3-VL 2B", "https://example.invalid", 1_600_000_000, "x"),
)

@IrohacoPreviews
@Composable
private fun AiSettingsPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AiSettings(
                    models = listOf(previewEmbedding) + previewAnswers,
                    states = mapOf("e" to ModelInstallState.Installed("/e"), "a1" to ModelInstallState.Downloading(1_100_000_000, 2_600_000_000)),
                    importResult = null,
                    onDownload = {},
                    onImport = {},
                    onDelete = {},
                    answerModels = previewAnswers,
                    remoteAnswerModels = listOf(RemoteModel("p", "c", "vision-large", "Vision Large", emptySet(), parameters = mapOf("reasoning_effort" to "high"))),
                    answerModel = "p",
                    connections = listOf(RemoteConnection("c", RemoteProtocol.OPENAI_CHAT, "https://api.example.com/v1")),
                )
            }
        }
    }
}
