package dev.irohaco

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import dev.irohaco.core.designsystem.R as DesignR
import dev.irohaco.feature.timeline.R as TimelineR

/** L2 end to end: a real video from MediaStore plays in the viewer. */
@RunWith(AndroidJUnit4::class)
class VideoPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    // The app keeps its database open across tests; the resume-time sync picks this file up without a reset.
    private val video = insertTestVideo(context, instrumentation.context.assets)
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
        .around(compose)

    @After
    fun tearDown() {
        context.contentResolver.delete(video, null, null)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun playsAVideoFromTheTimeline() {
        val thumbnail = hasContentDescription(context.getString(TimelineR.string.timeline_video_description, ""), substring = true)
        compose.waitUntilAtLeastOneExists(thumbnail, timeoutMillis = 15_000)
        compose.onAllNodes(thumbnail).onFirst().performClick()

        val play = hasContentDescription(context.getString(TimelineR.string.viewer_play))
        compose.waitUntilAtLeastOneExists(play, timeoutMillis = 5_000)
        compose.onAllNodes(play).onFirst().performClick()

        compose.waitUntilAtLeastOneExists(hasStateDescription(context.getString(DesignR.string.video_playing)), timeoutMillis = 10_000)
    }
}
