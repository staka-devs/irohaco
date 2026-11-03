package dev.irohaco

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** API keys are kept encrypted with a Keystore key, outside what backups take (API-008). */
@RunWith(AndroidJUnit4::class)
class KeystoreCredentialsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val credentials = KeystoreCredentials(context, alias = "irohaco-test-keys")

    @Test
    fun aKeyComesBackAsSavedAndIsNotOnDiskInTheClear() {
        val ref = credentials.save("sk-test-0123456789")
        val file = File(context.noBackupFilesDir, "credentials/$ref")
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("sk-test"))
        assertEquals("sk-test-0123456789", credentials.read(ref))
        credentials.delete(ref)
        assertNull(credentials.read(ref))
    }

    @Test
    fun aReferenceThatIsNotOneOfOursReadsNothing() {
        assertNull(credentials.read("../../databases/irohaco.db"))
    }
}
