package dev.irohaco.infrastructure.preferences

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.irohaco.core.contracts.ModelMarkStyle
import dev.irohaco.core.contracts.ModelMarks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Each mark as two preferences per model; the picture is kept as a small PNG, so the original may go. */
class DataStoreModelMarks(private val store: DataStore<Preferences>, private val resolver: () -> ContentResolver) : ModelMarks {
    override val marks: Flow<Map<String, ModelMarkStyle>> = store.data.map { prefs ->
        val ids = prefs.asMap().keys.mapNotNull { key -> key.name.removePrefix(COLOR).takeIf { it != key.name } ?: key.name.removePrefix(IMAGE).takeIf { it != key.name } }
        ids.toSet().associateWith { id ->
            ModelMarkStyle(prefs[longPreferencesKey(COLOR + id)], prefs[stringPreferencesKey(IMAGE + id)]?.let { Base64.decode(it, Base64.NO_WRAP) })
        }
    }.distinctUntilChanged { a, b -> a.keys == b.keys && a.all { (id, s) -> s.color == b[id]?.color && s.image.contentEquals(b[id]?.image) } }

    override suspend fun setColor(id: String, color: Long?) {
        store.edit { if (color == null) it.remove(longPreferencesKey(COLOR + id)) else it[longPreferencesKey(COLOR + id)] = color }
    }

    override suspend fun setImage(id: String, uri: String?) {
        val png = uri?.let { withContext(Dispatchers.IO) { small(Uri.parse(it)) } }
        store.edit { if (png == null) it.remove(stringPreferencesKey(IMAGE + id)) else it[stringPreferencesKey(IMAGE + id)] = Base64.encodeToString(png, Base64.NO_WRAP) }
    }

    private fun small(uri: Uri): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver(), uri)) { decoder, info, _ ->
            val scale = SIDE.toFloat() / minOf(info.size.width, info.size.height)
            if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    private companion object {
        const val COLOR = "model_mark_color_"
        const val IMAGE = "model_mark_image_"

        /** Twice the largest mark on screen, in pixels on a dense display. */
        const val SIDE = 144
    }
}
