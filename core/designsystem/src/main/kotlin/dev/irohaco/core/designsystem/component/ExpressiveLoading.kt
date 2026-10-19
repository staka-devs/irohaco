package dev.irohaco.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.irohaco.core.designsystem.R
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

/**
 * The indicator for short waits of one to a few seconds (DESIGN.md §3). It wraps the experimental
 * M3 Expressive LoadingIndicator so the opt-in stays in core:designsystem (DESIGN §1).
 *
 * @param contained true draws it on a container, for use over content such as photos.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveLoading(modifier: Modifier = Modifier, contained: Boolean = false) {
    val description = stringResource(R.string.loading)
    val semantics = modifier.semantics { contentDescription = description }
    if (contained) ContainedLoadingIndicator(semantics) else LoadingIndicator(semantics)
}

@IrohacoPreviews
@Composable
private fun ExpressiveLoadingPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            Row(Modifier.padding(IrohacoSpacing.l), horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.l)) {
                ExpressiveLoading()
                ExpressiveLoading(contained = true)
            }
        }
    }
}
