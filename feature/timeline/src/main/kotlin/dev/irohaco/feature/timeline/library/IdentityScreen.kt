package dev.irohaco.feature.timeline.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.irohaco.core.application.ReconcileIdentity
import dev.irohaco.core.contracts.IdentityCandidate
import dev.irohaco.core.designsystem.component.MediaThumbnail
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.feature.timeline.R
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class IdentityViewModel(private val reconcile: ReconcileIdentity) : ViewModel() {
    val candidates: StateFlow<List<IdentityCandidate>> =
        reconcile.pending().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun same(candidate: IdentityCandidate) {
        viewModelScope.launch { reconcile.sameFile(candidate) }
    }

    fun different(candidate: IdentityCandidate) {
        viewModelScope.launch { reconcile.differentFile(candidate) }
    }

    fun recheck() {
        viewModelScope.launch { reconcile.recheckMissing() }
    }
}

/** Files that may be missing ones seen again; the user decides each pair (UC-ID-01). */
@Composable
fun IdentityScreen(viewModel: IdentityViewModel, onBack: () -> Unit) {
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    IdentityContent(candidates, onBack, viewModel::same, viewModel::different, viewModel::recheck)
}

@Composable
internal fun IdentityContent(
    candidates: List<IdentityCandidate>,
    onBack: () -> Unit,
    onSame: (IdentityCandidate) -> Unit,
    onDifferent: (IdentityCandidate) -> Unit,
    onRecheck: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.identity_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.viewer_back)) }
                },
                actions = {
                    IconButton(onClick = onRecheck) { Icon(painterResource(IrohacoIcons.Refresh), stringResource(R.string.identity_recheck)) }
                },
            )
        },
    ) { padding ->
        if (candidates.isEmpty()) {
            Text(
                stringResource(R.string.identity_none),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(padding).padding(IrohacoSpacing.xxl),
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
        ) {
            item {
                Text(
                    stringResource(R.string.identity_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
                )
            }
            items(candidates, key = { "${it.discoveryId}/${it.missingAssetId}" }) { candidate ->
                CandidateRow(candidate, onSame, onDifferent)
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: IdentityCandidate, onSame: (IdentityCandidate) -> Unit, onDifferent: (IdentityCandidate) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.m),
        horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.l),
        verticalAlignment = Alignment.Top,
    ) {
        MediaThumbnail(model = candidate.newContentUri, contentDescription = candidate.fileName, selected = false, modifier = Modifier.size(88.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
            Text(candidate.fileName, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.identity_was, candidate.missingFolder.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.identity_now, candidate.newFolder.orEmpty()), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                FilledTonalButton(onClick = { onSame(candidate) }) { Text(stringResource(R.string.identity_same)) }
                TextButton(onClick = { onDifferent(candidate) }) { Text(stringResource(R.string.identity_different)) }
            }
        }
    }
}

@IrohacoPreviews
@Composable
private fun IdentityPreview() {
    IrohacoTheme(dynamicColor = false) {
        IdentityContent(
            candidates = listOf(IdentityCandidate("d1", "old", "new", "IMG_0420.jpg", "DCIM/Camera/", "Pictures/Trip/", "content://preview/1")),
            onBack = {}, onSame = {}, onDifferent = {}, onRecheck = {},
        )
    }
}
