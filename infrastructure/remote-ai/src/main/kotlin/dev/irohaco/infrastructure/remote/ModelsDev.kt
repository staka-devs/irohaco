package dev.irohaco.infrastructure.remote

import dev.irohaco.core.contracts.ModelInfo
import dev.irohaco.core.contracts.ModelOptions
import dev.irohaco.core.contracts.RemoteCallException
import dev.irohaco.core.contracts.RemoteFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

/**
 * The public list of models at models.dev, read only when the user asks for a model's options: one GET of the
 * whole list, with nothing of the user's in it; the model is looked up on the device.
 */
class ModelsDev(private val url: String = URL_, private val timeoutMs: Int = TIMEOUT_MS) : ModelInfo {
    override suspend fun options(baseUrl: String, modelId: String): ModelOptions? = withContext(Dispatchers.IO) {
        find(JSONObject(get()), baseUrl, modelId)
    }

    private fun get(): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            val status = connection.responseCode
            if (status !in 200..299) throw RemoteCallException(RemoteFailure.Failed(status, ""))
            return connection.inputStream.bufferedReader().use { it.readText() }
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

    companion object {
        private const val URL_ = "https://models.dev/api.json"
        private const val TIMEOUT_MS = 30_000

        /** The providers models.dev lists without their address, by the host of their API. */
        private val HOSTS = mapOf(
            "api.openai.com" to "openai",
            "api.anthropic.com" to "anthropic",
            "generativelanguage.googleapis.com" to "google",
        )

        /** The provider at [baseUrl]'s host first, then any other that lists [modelId], since a service may resell a model. */
        internal fun find(list: JSONObject, baseUrl: String, modelId: String): ModelOptions? {
            val host = runCatching { URI(baseUrl.trim()).host }.getOrNull()?.lowercase()
            val providers = list.keys().asSequence().mapNotNull(list::optJSONObject).toList()
            fun hostOf(provider: JSONObject) = runCatching { URI(provider.optString("api")).host }.getOrNull()?.lowercase()
            val (same, others) = providers.partition { HOSTS[host] == it.optString("id") || (host != null && hostOf(it) == host) }
            val model = (same + others).firstNotNullOfOrNull { it.optJSONObject("models")?.optJSONObject(modelId) } ?: return null
            val reasoning = model.optJSONArray("reasoning_options")
            val options = (0 until (reasoning?.length() ?: 0)).mapNotNull { reasoning?.optJSONObject(it) }
            fun option(type: String) = options.firstOrNull { it.optString("type") == type }
            return ModelOptions(
                efforts = option("effort")?.optJSONArray("values")?.let { values -> (0 until values.length()).map(values::getString) },
                minBudget = option("budget_tokens")?.takeIf { it.has("min") }?.optInt("min"),
                maxOutput = model.optJSONObject("limit")?.takeIf { it.has("output") }?.optInt("output"),
            )
        }
    }
}
