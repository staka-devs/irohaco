package dev.irohaco.infrastructure.remote

import dev.irohaco.core.contracts.ListedModel
import dev.irohaco.core.contracts.ModelInfo
import dev.irohaco.core.contracts.ModelKind
import dev.irohaco.core.contracts.ModelOptions
import dev.irohaco.core.contracts.RemoteCallException
import dev.irohaco.core.contracts.RemoteFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // Read once per run of the app: it is about 6 MB, and the user may look several models up in a row.
    private val cached = Mutex()
    private var read: JSONObject? = null

    internal suspend fun list(): JSONObject = cached.withLock { read ?: withContext(Dispatchers.IO) { JSONObject(get()) }.also { read = it } }

    override suspend fun options(baseUrl: String, modelId: String): ModelOptions? = find(list(), baseUrl, modelId)

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

        private val DATED = Regex("""-(\d{8}|\d{4}-\d{2}-\d{2}|latest)$""")

        internal fun names(modelId: String): List<String> {
            val typed = modelId.trim()
            val lower = typed.lowercase()
            val bare = lower.substringAfterLast('/')
            return listOf(typed, lower, bare, DATED.replace(bare, "")).distinct()
        }

        /** The providers models.dev lists without their address, by the host of their API. */
        private val HOSTS = mapOf(
            "api.openai.com" to "openai",
            "api.anthropic.com" to "anthropic",
            "generativelanguage.googleapis.com" to "google",
        )

        /** The providers at [baseUrl]'s host, then the others. */
        internal fun providers(list: JSONObject, baseUrl: String): Pair<List<JSONObject>, List<JSONObject>> {
            val host = runCatching { URI(baseUrl.trim()).host }.getOrNull()?.lowercase()
            val providers = list.keys().asSequence().mapNotNull(list::optJSONObject).toList()
            fun hostOf(provider: JSONObject) = runCatching { URI(provider.optString("api")).host }.getOrNull()?.lowercase()
            return providers.partition { HOSTS[host] == it.optString("id") || (host != null && hostOf(it) == host) }
        }

        /** What one models.dev entry says a model is. */
        internal fun listed(model: JSONObject): ListedModel {
            val type = if (model.isNull("type")) null else model.optString("type")
            val inputs = model.optJSONObject("modalities")?.optJSONArray("input")
            return ListedModel(
                id = model.optString("id"),
                name = model.optString("name").ifEmpty { null },
                kind = when (type) {
                    null, "chat" -> ModelKind.CHAT
                    "embedding" -> ModelKind.EMBEDDING
                    else -> ModelKind.OTHER
                },
                readsImages = inputs?.let { array -> (0 until array.length()).any { array.optString(it) == "image" } },
            )
        }

        /** The models.dev entry of [modelId] at [baseUrl], read the way [find] reads it. */
        internal fun entry(list: JSONObject, baseUrl: String, modelId: String): JSONObject? {
            val (same, others) = providers(list, baseUrl)
            return names(modelId).firstNotNullOfOrNull { id -> (same + others).firstNotNullOfOrNull { it.optJSONObject("models")?.optJSONObject(id) } }
        }

        /**
         * The provider at [baseUrl]'s host first, then any other that lists the model, since a service may resell one.
         * The ID is tried as typed, then in lower case, without a vendor prefix (`openai/gpt-x`) and without a date
         * or `-latest` at its end, as services name the same model.
         */
        internal fun find(list: JSONObject, baseUrl: String, modelId: String): ModelOptions? {
            val model = entry(list, baseUrl, modelId) ?: return null
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
