package dev.irohaco

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import dev.irohaco.feature.timeline.R as TimelineR

/** L2 end to end: a real image in MediaStore shows up on the timeline after the resume-time sync. */
@RunWith(AndroidJUnit4::class)
class TimelineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // The app keeps its database open across tests; the resume-time sync picks this file up without a reset.
    private val image = insertTestImage(context)
    private val compose = createAndroidComposeRule<MainActivity>()

    // Files earlier tests deleted stay on the timeline until a sync; without this the grid could open one of them first.
    private val syncFirst = object : ExternalResource() {
        override fun before() = runBlocking { (context.applicationContext as IrohacoApplication).container.syncCatalog() }
    }

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
        .around(syncFirst)
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

        // Long press enters selection mode with the toolbar; back leaves it.
        compose.onAllNodes(thumbnail).onFirst().performTouchInput { longClick() }
        val share = hasContentDescription(context.getString(TimelineR.string.timeline_share))
        compose.waitUntilAtLeastOneExists(share, timeoutMillis = 5_000)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilDoesNotExist(share, timeoutMillis = 5_000)

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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun opensTheFolderFromTheLibraryAndItsPhotoInTheViewer() {
        compose.onNode(hasText(context.getString(R.string.destination_library))).performClick()
        // insertTestImage puts the file in Pictures/IrohacoTest/.
        val folder = hasText("IrohacoTest")
        compose.waitUntilAtLeastOneExists(folder, timeoutMillis = 15_000)
        compose.onAllNodes(folder).onFirst().performClick()

        val thumbnail = hasContentDescription(context.getString(TimelineR.string.timeline_photo_description, ""), substring = true)
        compose.waitUntilAtLeastOneExists(thumbnail, timeoutMillis = 5_000)
        compose.onAllNodes(thumbnail).onFirst().performClick()
        val back = hasContentDescription(context.getString(TimelineR.string.viewer_back))
        compose.waitUntilAtLeastOneExists(hasContentDescription(context.getString(TimelineR.string.details_open)), timeoutMillis = 5_000)

        // Back from the viewer returns to the folder, not to the tabs.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(thumbnail, timeoutMillis = 5_000)
        compose.waitUntilAtLeastOneExists(back, timeoutMillis = 5_000) // the folder's own back button
    }
}
