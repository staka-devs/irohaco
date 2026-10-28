package dev.irohaco.infrastructure.ai

import android.content.Context
import android.net.Uri
import dev.irohaco.core.contracts.ModelStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Model files in the app's own external files, removed with the app (M2-02). A file becomes
 * `<id>.litertlm` only after its digest was checked; until then it is a `.partial`.
 */
class AndroidModelStorage(private val context: Context) : ModelStorage {
    private val dir: File get() = File(context.getExternalFilesDir(null), MODELS).apply { mkdirs() }

    override fun installed(id: String): String? = File(dir, "$id.litertlm").takeIf { it.isFile }?.path

    override fun partial(id: String): String = File(dir, "$id.partial").path

    override fun partialBytes(id: String): Long = File(dir, "$id.partial").length()

    override suspend fun copyIn(sourceUri: String): String = withContext(Dispatchers.IO) {
        val target = File(dir, "import-${UUID.randomUUID()}.partial")
        val input = context.contentResolver.openInputStream(Uri.parse(sourceUri)) ?: throw IOException("Cannot open $sourceUri")
        try {
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        target.path
    }

    override suspend fun sha256(path: String): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    override suspend fun promote(path: String, id: String): String = withContext(Dispatchers.IO) {
        val target = File(dir, "$id.litertlm")
        target.delete()
        if (!File(path).renameTo(target)) throw IOException("Cannot move $path into place")
        target.path
    }

    override suspend fun discard(path: String) {
        withContext(Dispatchers.IO) { File(path).delete() }
    }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            File(dir, "$id.litertlm").delete()
            File(dir, "$id.partial").delete()
        }
    }

    private companion object {
        const val MODELS = "models"
    }
}
