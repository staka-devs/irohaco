package dev.irohaco

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder

class IrohacoApplication : Application(), SingletonImageLoader.Factory {
    val container by lazy { AppContainer(this) }

    /**
     * Grid cells come from MediaStore's small copies (M1-25); a video without one falls back to a
     * decoded frame, so the timeline can mix photos and videos (PRD GAL-001).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components {
            add(GridThumbnailFetcher.Factory(this@IrohacoApplication))
            add(GridThumbnailFetcher.Key())
            add(VideoFrameDecoder.Factory())
        }.build()
}
