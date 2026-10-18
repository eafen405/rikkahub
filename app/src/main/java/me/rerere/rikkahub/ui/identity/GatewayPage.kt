package me.rerere.rikkahub.ui.identity

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.remote.AccountSession
import me.rerere.rikkahub.data.remote.GatewayAsrDraft
import me.rerere.rikkahub.data.remote.GatewayBackend
import me.rerere.rikkahub.data.remote.GatewayConfig
import me.rerere.rikkahub.data.remote.GatewayEditable
import me.rerere.rikkahub.data.remote.GatewayRoute
import me.rerere.rikkahub.data.remote.GatewayRouteDraft
import me.rerere.rikkahub.data.remote.GatewayRouteLock
import me.rerere.rikkahub.data.remote.GatewaySelection
import me.rerere.rikkahub.data.remote.GatewaySnapshot
import me.rerere.rikkahub.data.remote.GatewayState
import me.rerere.rikkahub.data.remote.lock
import org.koin.compose.koinInject

/**
 * The administrator's shared gateway.
 *
 * Read-only first: the current shared selection, every provider route with its
 * credential reference and models, and the ASR connection with its availability.
 * Editing is offered per section and only where the backend says the phone may
 * write; a route a running reply still uses is shown locked with the reason, and
 * a refusal is shown with the backend's own code and message.
 *
 * No secret has an input anywhere on this page. Credentials are shown by the
 * environment-variable NAME the backend reads, never by value: the phone cannot
 * leak what it never receives.
 *
 * The page composes nothing itself for an ordinary agent: the navigation gate
 * refuses [me.rerere.rikkahub.Screen.Gateway] for them, so there is no hidden
 * button standing between an agent and these settings.
 */
@Composable
fun GatewayPage(
    onBack: () -> Unit,
    session: AccountSession = koinInject(),
    backend: GatewayBackend = koinInject(),
) {
    val identity by session.identity.collectAsStateWithLifecycle()
    val token = identity?.token
    val scope = rememberCoroutineScope()
    val state = remember(backend, scope) { GatewayState(backend, scope) }
    LaunchedEffect(token) {
        if (token != null) state.load(token)
    }
    val snapshot by state.snapshot.collectAsStateWithLifecycle()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = stringResource(R.string.gateway_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.gateway_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (token == null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.gateway_signed_out),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            FailureBanner(snapshot)

            if (snapshot.loading && snapshot.config == null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.gateway_loading))
                }
            }

            snapshot.config?.let { config ->
                if (snapshot.working) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.identity_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(20.dp))
                SelectionSection(
                    config = config,
                    busy = snapshot.working,
                    canWrite = token != null,
                    onSave = { selection -> token?.let { state.select(it, selection) } },
                )

                Spacer(Modifier.height(20.dp))
                RoutesSection(
                    config = config,
                    busy = snapshot.working,
                    canWrite = token != null,
                    onSave = { draft, saved -> token?.let { state.saveRoute(it, draft, saved) } },
                    onDelete = { provider -> token?.let { state.deleteRoute(it, provider) } },
                )

                Spacer(Modifier.height(20.dp))
                AsrSection(
                    config = config,
                    busy = snapshot.working,
                    canWrite = token != null,
                    onSave = { draft -> token?.let { state.saveAsr(it, draft) } },
                )
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = { token?.let(state::load) },
                enabled = token != null && !snapshot.loading && !snapshot.working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.gateway_refresh_action))
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.gateway_back_action))
            }
        }
    }
}

/**
 * The backend's own words, or the localized "unreachable" for a lost
 * connection. A refusal is never folded into a generic message: the code is what
 * tells "not the administrator" from "a running reply still uses this route".
 */
@Composable
private fun FailureBanner(snapshot: GatewaySnapshot) {
    val failure = snapshot.failure ?: return
    Spacer(Modifier.height(12.dp))
    Text(
        text = if (failure.unreachable) {
            stringResource(R.string.gateway_unreachable)
        } else {
            "${failure.code}: ${failure.message}"
        },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SavedLine(saved: Boolean) {
    Text(
        text = stringResource(if (saved) R.string.gateway_saved_yes else R.string.gateway_saved_no),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SelectionSection(
    config: GatewayConfig,
    busy: Boolean,
    canWrite: Boolean,
    onSave: (GatewaySelection) -> Unit,
) {
    val selection = config.selection
    var provider by remember(selection) { mutableStateOf(selection.provider) }
    var model by remember(selection) { mutableStateOf(selection.model) }
    var effort by remember(selection) { mutableStateOf(selection.reasoningEffort.orEmpty()) }
    val models = config.routes.firstOrNull { it.provider == provider }?.models.orEmpty()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.gateway_selection_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.gateway_selection_next_turn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${selection.provider} / ${selection.model}" +
                    selection.reasoningEffort?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
            )
            SavedLine(saved = config.saved.selection)

            if (!config.editable.selection) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.gateway_read_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Spacer(Modifier.height(12.dp))
            PickerField(
                label = stringResource(R.string.gateway_route_provider),
                value = provider,
                options = config.routes.map { it.provider },
                enabled = !busy,
                onPick = { picked ->
                    provider = picked
                    model = config.routes.firstOrNull { it.provider == picked }?.models?.firstOrNull()?.id.orEmpty()
                },
            )
            Spacer(Modifier.height(8.dp))
            PickerField(
                label = stringResource(R.string.gateway_selection_model),
                value = model,
                options = models.map { it.id },
                enabled = !busy,
                onPick = { model = it },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = effort,
                onValueChange = { effort = it },
                label = { Text(stringResource(R.string.gateway_selection_reasoning)) },
                supportingText = { Text(stringResource(R.string.gateway_selection_reasoning_hint)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    onSave(
                        GatewaySelection(
                            provider = provider.trim(),
                            model = model.trim(),
                            reasoningEffort = effort.trim().takeIf { it.isNotEmpty() },
                        ),
                    )
                },
                enabled = canWrite && !busy && provider.isNotBlank() && model.isNotBlank(),
            ) {
                Text(stringResource(R.string.gateway_selection_save))
            }
        }
    }
}

@Composable
private fun PickerField(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled && options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(value.ifEmpty { "—" })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun RoutesSection(
    config: GatewayConfig,
    busy: Boolean,
    canWrite: Boolean,
    onSave: (GatewayRouteDraft, onSaved: () -> Unit) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editing by remember(config) { mutableStateOf<RouteEditor?>(null) }
    var deleting by remember(config) { mutableStateOf<GatewayRoute?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.gateway_routes_title),
                style = MaterialTheme.typography.titleMedium,
            )
            SavedLine(saved = config.saved.routes)
            Spacer(Modifier.height(8.dp))

            if (config.routes.isEmpty()) {
                Text(
                    text = stringResource(R.string.gateway_routes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            config.routes.forEach { route ->
                Spacer(Modifier.height(12.dp))
                RouteCard(
                    route = route,
                    editable = config.editable,
                    busy = busy,
                    canWrite = canWrite,
                    onEdit = { editing = RouteEditor.editing(route) },
                    onDelete = { deleting = route },
                )
            }

            Spacer(Modifier.height(12.dp))
            val current = editing
            if (current == null) {
                Button(
                    onClick = { editing = RouteEditor.adding() },
                    enabled = canWrite && config.editable.routes && !busy,
                ) {
                    Text(stringResource(R.string.gateway_route_new))
                }
            } else {
                RouteEditorForm(
                    editor = current,
                    busy = busy,
                    canSave = canWrite && config.editable.routes,
                    onCancel = { editing = null },
                    onSave = { draft -> onSave(draft) { editing = null } },
                )
            }

            deleting?.let { route ->
                AlertDialog(
                    onDismissRequest = { if (!busy) deleting = null },
                    title = { Text(stringResource(R.string.gateway_route_delete_confirm)) },
                    text = { Text(route.provider) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                deleting = null
                                onDelete(route.provider)
                            },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.gateway_route_delete))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleting = null }, enabled = !busy) {
                            Text(stringResource(R.string.gateway_route_cancel))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RouteCard(
    route: GatewayRoute,
    editable: GatewayEditable,
    busy: Boolean,
    canWrite: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val lock = route.lock(editable)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = route.displayName?.takeIf { it.isNotBlank() } ?: route.provider,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = route.provider,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            route.api?.let { Text("${stringResource(R.string.gateway_route_api)}: $it") }
            route.baseURL?.let { Text("${stringResource(R.string.gateway_route_base_url)}: $it") }
            route.apiKeyEnv?.let {
                Text("${stringResource(R.string.gateway_route_api_key_env)}: $it")
            }
            Text(
                text = "${stringResource(R.string.gateway_route_credential)}: " +
                    route.credential.ref + " · " +
                    stringResource(
                        if (route.credential.configured) R.string.gateway_route_credential_configured
                        else R.string.gateway_route_credential_missing,
                    ) + " · " +
                    stringResource(
                        if (route.credential.writable) R.string.gateway_route_credential_writable
                        else R.string.gateway_route_credential_read_only,
                    ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (route.models.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.gateway_route_models),
                    style = MaterialTheme.typography.labelMedium,
                )
                route.models.forEach { model ->
                    Text(
                        text = buildString {
                            append(model.id)
                            model.name?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
                            model.contextWindow?.let { append(" · ctx $it") }
                            model.maxTokens?.let { append(" · max $it") }
                            model.input?.takeIf { it.isNotEmpty() }?.let { append(" · ${it.joinToString(",")}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (lock) {
                GatewayRouteLock.IN_USE -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.gateway_route_in_use),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                GatewayRouteLock.READ_ONLY -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.gateway_route_locked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                null -> Unit
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onEdit,
                    enabled = lock == null && canWrite && !busy,
                ) {
                    Text(stringResource(R.string.gateway_route_edit))
                }
                TextButton(
                    onClick = onDelete,
                    enabled = lock == null && canWrite && !busy,
                ) {
                    Text(stringResource(R.string.gateway_route_delete))
                }
            }
        }
    }
}

@Composable
private fun RouteEditorForm(
    editor: RouteEditor,
    busy: Boolean,
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: (GatewayRouteDraft) -> Unit,
) {
    // Local copies so every keystroke recomposes; [editor] is the value the form
    // opened with, not a mutable object shared with the list.
    var provider by remember(editor) { mutableStateOf(editor.provider) }
    var displayName by remember(editor) { mutableStateOf(editor.displayName) }
    var api by remember(editor) { mutableStateOf(editor.api) }
    var baseURL by remember(editor) { mutableStateOf(editor.baseURL) }
    var apiKeyEnv by remember(editor) { mutableStateOf(editor.apiKeyEnv) }
    var modelsText by remember(editor) { mutableStateOf(editor.modelsText) }

    val models = decodeModelsText(modelsText)
    val credentialLocked = editor.original?.credential?.writable == false
    val canSubmit = canSave && !busy && provider.isNotBlank() && models.invalidLines.isEmpty()

    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(
            if (editor.original == null) R.string.gateway_route_new else R.string.gateway_route_edit,
        ),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = provider,
        onValueChange = { provider = it },
        label = { Text(stringResource(R.string.gateway_route_provider)) },
        singleLine = true,
        enabled = editor.original == null && !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = displayName,
        onValueChange = { displayName = it },
        label = { Text(stringResource(R.string.gateway_route_display_name)) },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = api,
        onValueChange = { api = it },
        label = { Text(stringResource(R.string.gateway_route_api)) },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = baseURL,
        onValueChange = { baseURL = it },
        label = { Text(stringResource(R.string.gateway_route_base_url)) },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = apiKeyEnv,
        onValueChange = { apiKeyEnv = it },
        label = { Text(stringResource(R.string.gateway_route_api_key_env)) },
        supportingText = { Text(stringResource(R.string.gateway_route_api_key_env_hint)) },
        singleLine = true,
        // A reference the deployment reserves is shown but not rewritten; this
        // field carries a NAME, so there is still no secret entry here.
        enabled = !busy && !credentialLocked,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = modelsText,
        onValueChange = { modelsText = it },
        label = { Text(stringResource(R.string.gateway_route_models)) },
        supportingText = { Text(stringResource(R.string.gateway_route_models_hint)) },
        minLines = 3,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    if (models.invalidLines.isNotEmpty()) {
        Text(
            text = stringResource(
                R.string.gateway_route_invalid_models,
                models.invalidLines.joinToString(", "),
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                onSave(
                    GatewayRouteDraft(
                        provider = provider.trim(),
                        displayName = displayName.trim().takeIf { it.isNotEmpty() },
                        api = api.trim().takeIf { it.isNotEmpty() },
                        baseURL = baseURL.trim().takeIf { it.isNotEmpty() },
                        apiKeyEnv = apiKeyEnv.trim().takeIf { it.isNotEmpty() },
                        models = models.models,
                    ),
                )
            },
            enabled = canSubmit,
        ) {
            Text(stringResource(R.string.gateway_route_save))
        }
        TextButton(onClick = onCancel, enabled = !busy) {
            Text(stringResource(R.string.gateway_route_cancel))
        }
    }
}

@Composable
private fun AsrSection(
    config: GatewayConfig,
    busy: Boolean,
    canWrite: Boolean,
    onSave: (GatewayAsrDraft) -> Unit,
) {
    val asr = config.asr
    var model by remember(asr) { mutableStateOf(asr.model.orEmpty()) }
    var endpoint by remember(asr) { mutableStateOf(asr.endpoint.orEmpty()) }
    var maxDuration by remember(asr) { mutableStateOf(asr.maxDurationMs?.toString().orEmpty()) }
    var maxAudioBytes by remember(asr) { mutableStateOf(asr.maxAudioBytes?.toString().orEmpty()) }
    var timeout by remember(asr) { mutableStateOf(asr.timeoutMs?.toString().orEmpty()) }
    var maxGlobal by remember(asr) { mutableStateOf(asr.maxGlobalInFlight?.toString().orEmpty()) }
    var maxUser by remember(asr) { mutableStateOf(asr.maxUserInFlight?.toString().orEmpty()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.gateway_asr_title),
                style = MaterialTheme.typography.titleMedium,
            )
            SavedLine(saved = config.saved.asr)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (asr.available) R.string.gateway_asr_available else R.string.gateway_asr_unavailable,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (asr.available) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                text = "${stringResource(R.string.gateway_route_credential)}: " +
                    asr.credential.ref + " · " +
                    stringResource(
                        if (asr.credential.configured) R.string.gateway_route_credential_configured
                        else R.string.gateway_route_credential_missing,
                    ) + " · " +
                    stringResource(
                        if (asr.credential.writable) R.string.gateway_route_credential_writable
                        else R.string.gateway_route_credential_read_only,
                    ),
                style = MaterialTheme.typography.bodySmall,
            )

            if (!config.editable.asr) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.gateway_read_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.gateway_asr_model)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text(stringResource(R.string.gateway_asr_endpoint)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LimitField(maxDuration, { maxDuration = it }, R.string.gateway_asr_max_duration, busy)
            LimitField(maxAudioBytes, { maxAudioBytes = it }, R.string.gateway_asr_max_audio_bytes, busy)
            LimitField(timeout, { timeout = it }, R.string.gateway_asr_timeout, busy)
            LimitField(maxGlobal, { maxGlobal = it }, R.string.gateway_asr_max_global, busy)
            LimitField(maxUser, { maxUser = it }, R.string.gateway_asr_max_user, busy)

            val limits = listOf(maxDuration, maxAudioBytes, timeout, maxGlobal, maxUser)
                .map { it.trim().toIntOrNull() }
            val valid = limits.all { it != null } && model.isNotBlank() && endpoint.isNotBlank()
            if (!valid) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.gateway_asr_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (!valid) return@Button
                    onSave(
                        GatewayAsrDraft(
                            model = model.trim(),
                            endpoint = endpoint.trim(),
                            maxDurationMs = maxDuration.trim().toInt(),
                            maxAudioBytes = maxAudioBytes.trim().toInt(),
                            timeoutMs = timeout.trim().toInt(),
                            maxGlobalInFlight = maxGlobal.trim().toInt(),
                            maxUserInFlight = maxUser.trim().toInt(),
                        ),
                    )
                },
                enabled = canWrite && !busy && valid,
            ) {
                Text(stringResource(R.string.gateway_asr_save))
            }
        }
    }
}

@Composable
private fun LimitField(
    value: String,
    onChange: (String) -> Unit,
    @StringRes label: Int,
    busy: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The values one route editor opened with; the form owns the live text. */
private data class RouteEditor(
    val original: GatewayRoute?,
    val provider: String,
    val displayName: String,
    val api: String,
    val baseURL: String,
    val apiKeyEnv: String,
    val modelsText: String,
) {
    companion object {
        fun adding() = RouteEditor(
            original = null,
            provider = "",
            displayName = "",
            api = "",
            baseURL = "",
            apiKeyEnv = "",
            modelsText = "",
        )

        fun editing(route: GatewayRoute) = RouteEditor(
            original = route,
            provider = route.provider,
            displayName = route.displayName.orEmpty(),
            api = route.api.orEmpty(),
            baseURL = route.baseURL.orEmpty(),
            apiKeyEnv = route.apiKeyEnv.orEmpty(),
            modelsText = encodeModelsText(route.models),
        )
    }
}
