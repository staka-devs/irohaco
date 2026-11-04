package dev.irohaco.feature.timeline

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irohaco.core.application.PrepareShare
import dev.irohaco.core.contracts.LocationNotRemovedException
import dev.irohaco.core.contracts.ShareCandidate
import dev.irohaco.core.contracts.SharingSettings
import dev.irohaco.core.contracts.ShareLocationPolicy
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The user's GPS policy and the checks every screen that shares goes through (PRD GAL-005, MET-003). */
class ShareSupport(private val share: PrepareShare?, settings: SharingSettings?) {
    val policy: Flow<ShareLocationPolicy> = settings?.shareLocationPolicy ?: flowOf(ShareLocationPolicy.ASK)

    suspend fun check(items: List<TimelineItem>): List<ShareCandidate> = share?.check(items) ?: items.map { ShareCandidate(it, hasLocation = false) }

    suspend fun prepare(candidates: List<ShareCandidate>, stripLocation: Boolean): List<TimelineItem> =
        share?.prepare(candidates, stripLocation) ?: candidates.map { it.item }
}

/**
 * Asks about a shared photo's GPS as the policy says, before handing the final items to [onReady]
 * (PRD GAL-005, MET-003). Returns a plain callback the caller uses in place of an ordinary onShare.
 */
@Composable
internal fun rememberShareAction(sharing: ShareSupport, onReady: (List<TimelineItem>) -> Unit): (List<TimelineItem>) -> Unit {
    val policy by sharing.policy.collectAsStateWithLifecycle(ShareLocationPolicy.ASK)
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<List<ShareCandidate>?>(null) }
    var unremovable by remember { mutableStateOf<List<ShareCandidate>?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun ready(candidates: List<ShareCandidate>, strip: Boolean) {
        scope.launch {
            try {
                onReady(sharing.prepare(candidates, strip))
            } catch (e: LocationNotRemovedException) {
                failed = true
            }
        }
    }

    fun proceed(candidates: List<ShareCandidate>, strip: Boolean) {
        val cannotStrip = candidates.filter { it.cannotRemoveLocation }
        if (strip && cannotStrip.isNotEmpty()) {
            unremovable = candidates
        } else {
            ready(candidates, strip)
        }
    }

    if (failed) {
        AlertDialog(
            onDismissRequest = { failed = false },
            title = { Text(stringResource(R.string.share_location_failed_title)) },
            text = { Text(stringResource(R.string.share_location_failed_body)) },
            confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }

    asking?.let { candidates ->
        AskShareLocationDialog(
            onDismiss = { asking = null },
            onStrip = { asking = null; proceed(candidates, true) },
            onKeep = { asking = null; proceed(candidates, false) },
        )
    }
    unremovable?.let { candidates ->
        val cannotStrip = candidates.filter { it.cannotRemoveLocation }
        UnremovableLocationDialog(
            count = cannotStrip.size,
            onDismiss = { unremovable = null },
            onInclude = { unremovable = null; ready(candidates, true) },
            onExclude = {
                unremovable = null
                ready(candidates.filterNot { it.cannotRemoveLocation }, true)
            },
        )
    }

    return { items ->
        scope.launch {
            val candidates = sharing.check(items)
            when {
                candidates.none { it.hasLocation } -> ready(candidates, false)
                policy == ShareLocationPolicy.ASK -> asking = candidates
                policy == ShareLocationPolicy.STRIP -> proceed(candidates, true)
                policy == ShareLocationPolicy.KEEP -> proceed(candidates, false)
            }
        }
    }
}

/** Shown once, before sharing, when the policy is to ask (PRD MET-003). */
@Composable
internal fun AskShareLocationDialog(onDismiss: () -> Unit, onStrip: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_location_title)) },
        text = { Text(stringResource(R.string.share_location_body)) },
        confirmButton = { TextButton(onClick = onStrip) { Text(stringResource(R.string.share_location_strip)) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.share_location_keep)) } },
    )
}

/** [count] items (usually videos) whose location this app cannot remove (M3-04 is out of scope here). */
@Composable
internal fun UnremovableLocationDialog(count: Int, onDismiss: () -> Unit, onInclude: () -> Unit, onExclude: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_location_unremovable_title)) },
        text = { Text(pluralStringResource(R.plurals.share_location_unremovable_body, count, count)) },
        confirmButton = { TextButton(onClick = onInclude) { Text(stringResource(R.string.share_location_unremovable_include)) } },
        dismissButton = { TextButton(onClick = onExclude) { Text(stringResource(R.string.share_location_unremovable_exclude)) } },
    )
}

@IrohacoPreviews
@Composable
private fun AskShareLocationPreview() {
    IrohacoTheme(dynamicColor = false) { AskShareLocationDialog(onDismiss = {}, onStrip = {}, onKeep = {}) }
}

@IrohacoPreviews
@Composable
private fun UnremovableLocationPreview() {
    IrohacoTheme(dynamicColor = false) { UnremovableLocationDialog(count = 1, onDismiss = {}, onInclude = {}, onExclude = {}) }
}
