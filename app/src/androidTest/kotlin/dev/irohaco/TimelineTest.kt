package dev.irohaco

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import dev.irohaco.feature.timeline.R as TimelineR

/** L2 end to end: a real image in MediaStore shows up on the timeline after the resume-time sync. */
@RunWith(AndroidJUnit4::class)
class TimelineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // The app keeps its database open across tests; the resume-time sync picks this file up without a reset.
    private val image = insertTestImage(context)
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
        .around(compose)

    @After
    fun tearDown() {
        context.contentResolver.delete(image, null, null)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun showsAPhotoFromMediaStoreAndOpensItInTheViewer() {
        val thumbnail = hasContentDescription(context.getString(TimelineR.string.timeline_photo_description, ""), substring = true)
        compose.waitUntilAtLeastOneExists(thumbnail, timeoutMillis = 15_000)

        compose.onAllNodes(thumbnail).onFirst().performClick()
        val back = hasContentDescription(context.getString(TimelineR.string.viewer_back))
        compose.waitUntilAtLeastOneExists(back, timeoutMillis = 5_000)

        // The details sheet reads the real file: its name comes from MediaStore.
        compose.onNode(hasContentDescription(context.getString(TimelineR.string.details_open))).performClick()
        compose.waitUntilAtLeastOneExists(hasText("irohaco-l2-", substring = true), timeoutMillis = 5_000)
        Espresso.pressBack() // system back goes to the sheet's window and closes only the sheet
        compose.waitUntilDoesNotExist(hasText("irohaco-l2-", substring = true), timeoutMillis = 5_000)

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilDoesNotExist(back, timeoutMillis = 5_000)
        compose.waitUntilAtLeastOneExists(thumbnail, timeoutMillis = 5_000)
    }
}
