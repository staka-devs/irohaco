package dev.irohaco.infrastructure.remote

import dev.irohaco.core.application.remoteFailure
import dev.irohaco.core.contracts.RemoteCallException
import dev.irohaco.core.contracts.RemoteFailure
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * One POST to a remote model. A status outside 2xx, a timeout or no route is thrown as
 * [RemoteCallException] with [apiKey] hidden from the message (API-008); nothing is retried.
 */
internal fun <T> post(url: String, headers: Map<String, String>, body: JSONObject, apiKey: String, timeoutMs: Int, read: (BufferedReader) -> T): T {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val status = connection.responseCode
        if (status !in 200..299) {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw RemoteCallException(remoteFailure(status, connection.getHeaderField("Retry-After"), error, listOf(apiKey)))
        }
        return connection.inputStream.bufferedReader().use(read)
    } catch (e: SocketTimeoutException) {
        throw RemoteCallException(RemoteFailure.Timeout)
    } catch (e: RemoteCallException) {
        throw e
    } catch (e: IOException) {
        throw RemoteCallException(RemoteFailure.Unreachable)
    } finally {
        connection.disconnect()
    }
}
