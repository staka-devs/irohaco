package dev.irohaco.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import dev.irohaco.core.designsystem.R
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

/**
 * DESIGN.md §5. Feature code maps `embedding_spaces.lifecycle_state`, `media_sources.access_state`
 * and the other sources in that table onto these values.
 */
sealed interface BadgeStatus {
    /** lifecycle_state = READY */
    data object Ready : BadgeStatus

    /** lifecycle_state = BUILDING or VERIFYING */
    data class Building(val percent: Int) : BadgeStatus

    /** lifecycle_state = PLANNED */
    data object NotIndexed : BadgeStatus

    /** lifecycle_state = STALE */
    data object Stale : BadgeStatus

    /** lifecycle_state = FAILED */
    data class Failed(val reason: String? = null) : BadgeStatus

    /** lifecycle_state = DELETING */
    data object Deleting : BadgeStatus

    /** access_state = OFFLINE */
    data object Offline : BadgeStatus

    /** access_state = INACCESSIBLE */
    data object Inaccessible : BadgeStatus

    /** Derived from the index coverage. */
    data object LowCoverage : BadgeStatus

    /** No model profile is installed. */
    data object AiNotInstalled : BadgeStatus
}

/** Shows a status with an icon, text and color together, never color alone (DESIGN §5). */
@Composable
fun StatusBadge(status: BadgeStatus, modifier: Modifier = Modifier) {
    val style = status.style(MaterialTheme.colorScheme)
    Surface(modifier = modifier, shape = CircleShape, color = style.container, contentColor = style.content) {
        Row(
            modifier = Modifier.padding(horizontal = IrohacoSpacing.s, vertical = IrohacoSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // sp, so the icon grows with the label at large font scales.
            val iconSize = with(LocalDensity.current) { 18.sp.toDp() }
            Icon(painterResource(style.icon), contentDescription = null, modifier = Modifier.size(iconSize))
            Text(status.label(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

private class BadgeStyle(val container: Color, val content: Color, @DrawableRes val icon: Int)

private fun BadgeStatus.style(c: ColorScheme): BadgeStyle =
    when (this) {
        BadgeStatus.Ready -> BadgeStyle(c.primaryContainer, c.onPrimaryContainer, IrohacoIcons.CheckCircle)
        is BadgeStatus.Building -> BadgeStyle(c.secondaryContainer, c.onSecondaryContainer, IrohacoIcons.ProgressActivity)
        BadgeStatus.NotIndexed -> BadgeStyle(c.surfaceContainerHighest, c.onSurfaceVariant, IrohacoIcons.RadioButtonUnchecked)
        BadgeStatus.Stale -> BadgeStyle(c.tertiaryContainer, c.onTertiaryContainer, IrohacoIcons.Update)
        is BadgeStatus.Failed -> BadgeStyle(c.errorContainer, c.onErrorContainer, IrohacoIcons.Error)
        BadgeStatus.Deleting -> BadgeStyle(c.surfaceContainerHighest, c.onSurfaceVariant, IrohacoIcons.Delete)
        BadgeStatus.Offline -> BadgeStyle(c.surfaceContainerHighest, c.onSurfaceVariant, IrohacoIcons.SdCardAlert)
        BadgeStatus.Inaccessible -> BadgeStyle(c.errorContainer, c.onErrorContainer, IrohacoIcons.Lock)
        BadgeStatus.LowCoverage -> BadgeStyle(c.tertiaryContainer, c.onTertiaryContainer, IrohacoIcons.DataAlert)
        BadgeStatus.AiNotInstalled -> BadgeStyle(c.surfaceContainerHigh, c.onSurfaceVariant, IrohacoIcons.SmartToy)
    }

@Composable
private fun BadgeStatus.label(): String =
    when (this) {
        BadgeStatus.Ready -> stringResource(R.string.status_ready)
        is BadgeStatus.Building -> stringResource(R.string.status_building, percent)
        BadgeStatus.NotIndexed -> stringResource(R.string.status_not_indexed)
        BadgeStatus.Stale -> stringResource(R.string.status_stale)
        is BadgeStatus.Failed ->
            reason?.let { stringResource(R.string.status_failed_with_reason, it) }
                ?: stringResource(R.string.status_failed)
        BadgeStatus.Deleting -> stringResource(R.string.status_deleting)
        BadgeStatus.Offline -> stringResource(R.string.status_offline)
        BadgeStatus.Inaccessible -> stringResource(R.string.status_inaccessible)
        BadgeStatus.LowCoverage -> stringResource(R.string.status_low_coverage)
        BadgeStatus.AiNotInstalled -> stringResource(R.string.status_ai_not_installed)
    }

@IrohacoPreviews
@Composable
private fun StatusBadgePreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            Column(Modifier.padding(IrohacoSpacing.l), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                listOf(
                    BadgeStatus.Ready,
                    BadgeStatus.Building(42),
                    BadgeStatus.NotIndexed,
                    BadgeStatus.Stale,
                    BadgeStatus.Failed(),
                    BadgeStatus.Failed("Out of storage"),
                    BadgeStatus.Deleting,
                    BadgeStatus.Offline,
                    BadgeStatus.Inaccessible,
                    BadgeStatus.LowCoverage,
                    BadgeStatus.AiNotInstalled,
                ).forEach { StatusBadge(it) }
            }
        }
    }
}
