package dev.irohaco.core.designsystem.component

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import dev.irohaco.core.designsystem.R
import androidx.media3.ui.compose.material3.Player as Media3Player

/**
 * Video surface with Media3's Material 3 controls (PRD GAL-004). Media3's Compose UI is still marked
 * unstable, so the opt-in stays here (DESIGN §1). TalkBack hears whether the video is playing.
 *
 * @param showControls follows the viewer's overlay toggle, so a tap shows and hides both together.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class) // androidx.annotation.OptIn: Media3 uses AndroidX opt-in markers
@Composable
fun VideoPlayer(player: Player, showControls: Boolean, modifier: Modifier = Modifier) {
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val state = stringResource(if (playing) R.string.video_playing else R.string.video_paused)
    Media3Player(
        player = player,
        modifier = modifier.semantics { stateDescription = state },
        showControls = showControls,
        topControls = null, // the viewer has its own top bar
    )
}
