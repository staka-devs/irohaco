package dev.irohaco.infrastructure.ai

import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a model over HTTPS into a partial file and resumes it with a Range request, the way AI Edge
 * Gallery's download worker does, so a download can be paused and picks up after a dropped connection
 * (M2-02). A server that ignores the range sends the whole file again, which then starts over.
 */
class HttpModelDownloader : ModelDownloader {
    override suspend fun fetch(artifact: ModelArtifact, destination: String, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val file = File(destination)
            var start = if (file.isFile) file.length() else 0L
            val connection = URL(artifact.url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                if (start > 0) connection.setRequestProperty("Range", "bytes=$start-")
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> Unit
                    HttpURLConnection.HTTP_OK -> start = 0 // no range support: start over
                    RANGE_NOT_SATISFIABLE -> if (start >= artifact.sizeBytes) return@withContext else throw IOException("HTTP $code")
                    else -> throw IOException("HTTP $code")
                }
                val total = start + connection.contentLengthLong.takeIf { it >= 0 }.let { it ?: (artifact.sizeBytes - start) }
                var done = start
                var reported = done
                onProgress(done, total)
                connection.inputStream.use { input ->
                    FileOutputStream(file, start > 0).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            ensureActive() // a pause or a cancel stops between chunks, keeping what was written
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (done - reported >= REPORT_BYTES) {
                                onProgress(done, total)
                                reported = done
                            }
                        }
                    }
                }
                onProgress(done, total)
                if (done < total) throw IOException("Connection closed at $done of $total bytes")
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val TIMEOUT_MS = 30_000
        const val RANGE_NOT_SATISFIABLE = 416
        const val BUFFER_BYTES = 1 shl 16
        const val REPORT_BYTES = 1L shl 20
    }
}
