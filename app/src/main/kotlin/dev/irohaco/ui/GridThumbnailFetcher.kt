package dev.irohaco.ui

import android.content.Context
import android.net.Uri
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import coil3.toUri
import dev.irohaco.core.designsystem.component.GridThumbnail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Loads a grid cell from MediaStore's own small copy (`ContentResolver.loadThumbnail`), which the OS
 * keeps on disk, so scrolling does not decode multi-megabyte originals or video frames (M1-25).
 * Without one, the original goes through Coil's usual fetchers and decoders, as before.
 */
class GridThumbnailFetcher(
    private val data: GridThumbnail,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val context: Context,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val px = thumbnailPx(options)
        val bitmap = withContext(LOADS) {
            try {
                context.contentResolver.loadThumbnail(Uri.parse(data.contentUri), Size(px, px), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }
        // Not "sampled": the small copy is the whole answer, so a cached one serves any cell of its size step.
        if (bitmap != null) return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.DISK)
        val original = data.contentUri.toUri()
        val (fetcher, _) = imageLoader.components.newFetcher(original, options, imageLoader) ?: return null
        return fetcher.fetch()
    }

    class Factory(private val context: Context) : Fetcher.Factory<GridThumbnail> {
        override fun create(data: GridThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            GridThumbnailFetcher(data, options, imageLoader, context)
    }

    /** One memory-cache entry per file and size step, so a dense grid keeps small copies in memory. */
    class Key : Keyer<GridThumbnail> {
        override fun key(data: GridThumbnail, options: Options): String = "${data.memoryKey}@${thumbnailPx(options)}"
    }

    private companion object {
        // A few decodes at a time, so scrolling keeps the CPU it needs to draw (M1-34).
        val LOADS = Dispatchers.IO.limitedParallelism(4)

        // 384 is sharp at three columns on a wide phone; a few fixed steps keep the OS's thumbnail cache reusable.
        val THUMBNAIL_PX = listOf(96, 192, 384)

        /** The smallest step that covers the cell, so 28 columns load a sixteenth of the pixels of four. */
        fun thumbnailPx(options: Options): Int {
            val cell = options.size.width.pxOrElse { THUMBNAIL_PX.last() }
            return THUMBNAIL_PX.firstOrNull { it >= cell } ?: THUMBNAIL_PX.last()
        }
    }
}
