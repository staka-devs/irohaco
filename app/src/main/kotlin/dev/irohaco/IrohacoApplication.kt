package dev.irohaco

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder

class IrohacoApplication : Application(), SingletonImageLoader.Factory {
    val container by lazy { AppContainer(this) }

    /** Video thumbnails come from a decoded frame, so the timeline can mix photos and videos (PRD GAL-001). */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build()
}
