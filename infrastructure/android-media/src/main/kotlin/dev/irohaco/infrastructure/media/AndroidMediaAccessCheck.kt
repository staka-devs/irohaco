package dev.irohaco.infrastructure.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dev.irohaco.core.contracts.MediaAccessCheck
import dev.irohaco.core.contracts.MediaLibraryAccessPort
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.LibraryAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full access lets every result through and no access none. Under partial access only the files the
 * user still has selected are let through: MediaStore lists those alone (ADR-0009, IST-008).
 */
class AndroidMediaAccessCheck(private val context: Context, private val access: MediaLibraryAccessPort) : MediaAccessCheck {
    override suspend fun readableNow(items: List<TimelineItem>): List<TimelineItem> {
        val now = access.current()
        val selected = items.filter { now.of(it.kind) == LibraryAccess.SELECTED }
        val visible = if (selected.isEmpty()) emptySet() else withContext(Dispatchers.IO) { visible(selected) }
        return items.filter {
            when (now.of(it.kind)) {
                LibraryAccess.FULL -> true
                LibraryAccess.SELECTED -> it.contentUri in visible
                LibraryAccess.NONE -> false
            }
        }
    }

    /** One query per collection; a content URI is its collection's URI and the MediaStore id. */
    private fun visible(items: List<TimelineItem>): Set<String> =
        items.groupBy { it.contentUri.substringBeforeLast('/') }.flatMap { (collection, inCollection) ->
            val ids = inCollection.map { it.contentUri.substringAfterLast('/') }
            try {
                context.contentResolver.query(
                    Uri.parse(collection),
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns._ID} IN (${ids.joinToString { "?" }})",
                    ids.toTypedArray(),
                    null,
                )?.use { cursor -> buildList { while (cursor.moveToNext()) add("$collection/${cursor.getLong(0)}") } }.orEmpty()
            } catch (e: SecurityException) {
                emptyList() // the grant went away while asking
            }
        }.toSet()
}
