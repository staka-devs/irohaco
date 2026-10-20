package dev.irohaco

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import java.util.UUID

/** Inserts a small PNG into MediaStore as this app; the caller deletes it. */
fun insertTestImage(context: Context): Uri {
    val resolver = context.contentResolver
    val uri = resolver.insert(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "irohaco-l2-${UUID.randomUUID()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/IrohacoTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        },
    )!!
    resolver.openOutputStream(uri)!!.use { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    return uri
}

/** Inserts the 2-second H.264 clip from the test assets into MediaStore as this app; the caller deletes it. */
fun insertTestVideo(context: Context, testAssets: android.content.res.AssetManager): Uri {
    val resolver = context.contentResolver
    val uri = resolver.insert(
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "irohaco-l2-${UUID.randomUUID()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/IrohacoTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        },
    )!!
    resolver.openOutputStream(uri)!!.use { out -> testAssets.open("test-video.mp4").use { it.copyTo(out) } }
    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    return uri
}
