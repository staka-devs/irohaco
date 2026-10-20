package dev.irohaco.infrastructure.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.irohaco.core.contracts.MediaLibraryAccessPort
import dev.irohaco.core.domain.LibraryAccess
import dev.irohaco.core.domain.MediaLibraryAccess

/** Reads the OS grants on every call (ADR-0009); images and videos are judged separately. */
class AndroidMediaLibraryAccess(private val context: Context) : MediaLibraryAccessPort {
    override fun current(): MediaLibraryAccess {
        val selected = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return MediaLibraryAccess(
            images = level(granted(Manifest.permission.READ_MEDIA_IMAGES), selected),
            videos = level(granted(Manifest.permission.READ_MEDIA_VIDEO), selected),
        )
    }

    private fun level(full: Boolean, selected: Boolean) = when {
        full -> LibraryAccess.FULL
        selected -> LibraryAccess.SELECTED
        else -> LibraryAccess.NONE
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
