package dev.irohaco

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.irohaco.core.database.IrohacoDatabaseFactory
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** L2 end to end: a real image in MediaStore shows up on the timeline after the resume-time sync. */
@RunWith(AndroidJUnit4::class)
class TimelineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val image = insertTestImage(context).also { context.deleteDatabase(IrohacoDatabaseFactory.NAME) }
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
    fun showsAPhotoFromMediaStore() {
        val photo = context.getString(dev.irohaco.feature.timeline.R.string.timeline_photo_description, "")
        compose.waitUntilAtLeastOneExists(hasContentDescription(photo, substring = true), timeoutMillis = 15_000)
    }
}
