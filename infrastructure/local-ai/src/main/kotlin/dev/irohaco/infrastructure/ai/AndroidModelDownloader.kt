package dev.irohaco.infrastructure.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import dev.irohaco.core.contracts.DownloadStatus
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads through the system service, which resumes after drops and keeps going while the app is
 * closed, with its own notification (M2-02). A download is found again by its description.
 */
class AndroidModelDownloader(private val context: Context) : ModelDownloader {
    private val manager get() = context.getSystemService(DownloadManager::class.java)

    override suspend fun start(artifact: ModelArtifact, destination: String) {
        withContext(Dispatchers.IO) {
            cancel(artifact)
            File(destination).delete()
            val request = DownloadManager.Request(Uri.parse(artifact.url))
                .setTitle(artifact.displayName)
                .setDescription(tag(artifact))
                .setDestinationUri(Uri.fromFile(File(destination)))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
            manager.enqueue(request)
        }
    }

    override fun status(artifact: ModelArtifact): Flow<DownloadStatus> = flow {
        while (true) {
            val row = find(artifact)
            if (row == null) {
                emit(DownloadStatus.None)
                return@flow
            }
            when (row.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    emit(DownloadStatus.Done(Uri.parse(row.localUri).path!!))
                    manager.remove(row.id) // the file stays; only the service's record goes
                    return@flow
                }
                DownloadManager.STATUS_FAILED -> {
                    manager.remove(row.id)
                    emit(DownloadStatus.Failed)
                    return@flow
                }
                else -> emit(DownloadStatus.Running(row.downloaded, row.total.takeIf { it > 0 } ?: artifact.sizeBytes))
            }
            delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(artifact: ModelArtifact) {
        withContext(Dispatchers.IO) { generateSequence { find(artifact) }.take(MAX_RECORDS).forEach { manager.remove(it.id) } }
    }

    private class Row(val id: Long, val status: Int, val downloaded: Long, val total: Long, val localUri: String?)

    private fun find(artifact: ModelArtifact): Row? =
        manager.query(DownloadManager.Query()).use { c ->
            val description = c.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)
            while (c.moveToNext()) {
                if (c.getString(description) != tag(artifact)) continue
                return Row(
                    id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                    status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    downloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
                )
            }
            null
        }

    private fun tag(artifact: ModelArtifact) = "irohaco-model:${artifact.id}"

    private companion object {
        const val POLL_MS = 500L
        const val MAX_RECORDS = 8
    }
}
