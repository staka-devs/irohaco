package dev.irohaco.core.designsystem.component

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import dev.irohaco.core.designsystem.R
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import kotlinx.coroutines.delay
import androidx.media3.ui.compose.material3.Player as Media3Player

/**
 * Video surface with Media3's Material 3 controls (PRD GAL-004). Media3's Compose UI is still marked
 * unstable, so the opt-in stays here (DESIGN §1). TalkBack hears whether the video is playing.
 *
 * @param showControls follows the viewer's overlay toggle, so a tap shows and hides both together.
 * @param scrubFrame draws the video's frame at a time; while the seek bar is dragged, it shows above
 *   the thumb with the time, so the user sees where the drag lands (#185).
 */
@OptIn(UnstableApi::class, ExperimentalApi::class) // androidx.annotation.OptIn: Media3 uses AndroidX opt-in markers
@Composable
fun VideoPlayer(
    player: Player,
    showControls: Boolean,
    modifier: Modifier = Modifier,
    scrubFrame: (@Composable (positionMs: Long, modifier: Modifier) -> Unit)? = null,
) {
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
        bottomControls = { _, visible ->
            AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) { SeekBar(player, scrubFrame) }
        },
    )
}

/**
 * A thin bar along the bottom with the time and the length above it, as video sites have it: the thumb
 * grows while held, and dragging shows the frame and time it would land on. The player seeks on release.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(player: Player, scrubFrame: (@Composable (Long, Modifier) -> Unit)?) {
    var positionMs by remember(player) { mutableLongStateOf(player.currentPosition) }
    var durationMs by remember(player) { mutableLongStateOf(player.duration) }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration
            delay(POSITION_POLL_MS)
        }
    }
    val known = durationMs != C.TIME_UNSET && durationMs > 0
    // Where the thumb is while held, from 0 to 1; null when not.
    var scrub by remember(player) { mutableStateOf<Float?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val held = interaction.collectIsDraggedAsState().value || interaction.collectIsPressedAsState().value
    val thumb by animateDpAsState(if (held) THUMB_HELD else THUMB, label = "seekThumb")
    val shownMs = scrub?.let { scrubPosition(it, durationMs) } ?: positionMs
    val played = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
            .padding(horizontal = IrohacoSpacing.m)
            .padding(top = IrohacoSpacing.l, bottom = IrohacoSpacing.xs),
    ) {
        if (known) scrub?.let { ScrubPreview(it, scrubPosition(it, durationMs), scrubFrame) }
        Text(
            if (known) "${formatPosition(shownMs)} / ${formatPosition(durationMs)}" else formatPosition(shownMs),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
        // The bar follows playback, and the thumb while held, as the value-taking Slider does with its state.
        val sliderState = remember(player) { SliderState() }
        sliderState.value = scrub ?: if (known) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        Slider(
            state = sliderState,
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                if (known) scrub?.let { player.seekTo(scrubPosition(it, durationMs)) }
                scrub = null
            },
            enabled = known,
            interactionSource = interaction,
            thumb = { _: SliderState ->
                Box(Modifier.size(THUMB_HELD), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(thumb).background(played, CircleShape))
                }
            },
            track = { sliderState: SliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(if (held) TRACK_HELD else TRACK),
                    colors = SliderDefaults.colors(activeTrackColor = played, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                )
            },
        )
    }
}

/** The frame and time at [fraction] of the video, following the thumb along the bar. */
@Composable
private fun ScrubPreview(fraction: Float, positionMs: Long, frame: (@Composable (Long, Modifier) -> Unit)?) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = IrohacoSpacing.xs)) {
        val x = (maxWidth - PREVIEW_WIDTH) * fraction
        Column(Modifier.offset(x = x), horizontalAlignment = Alignment.CenterHorizontally) {
            if (frame != null) {
                Box(Modifier.size(PREVIEW_WIDTH, PREVIEW_WIDTH * 9 / 16).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                    frame(positionMs, Modifier.matchParentSize())
                }
            }
            Text(formatPosition(positionMs), style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(top = IrohacoSpacing.xxs))
        }
    }
}

// Rounded to a hundredth of the video, so a drag reads at most a hundred frames from the file.
private fun scrubPosition(fraction: Float, durationMs: Long) = (fraction * 100).toInt() * durationMs / 100

/** 1:05 or 1:02:05. */
private fun formatPosition(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private const val POSITION_POLL_MS = 200L
private val PREVIEW_WIDTH = 160.dp
private val THUMB = 12.dp
private val THUMB_HELD = 18.dp
private val TRACK = 3.dp
private val TRACK_HELD = 5.dp
