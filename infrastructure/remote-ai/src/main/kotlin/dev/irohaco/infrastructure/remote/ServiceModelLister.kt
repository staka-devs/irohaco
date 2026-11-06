package dev.irohaco.infrastructure.remote

import dev.irohaco.core.contracts.ListedModel
import dev.irohaco.core.contracts.ModelKind
import dev.irohaco.core.contracts.ModelLister
import dev.irohaco.core.contracts.RemoteProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The models to offer when adding one: the service's own list where it has one, each told apart as models.dev
 * describes it, else what models.dev lists for the provider at that address. Fails only when neither can be read.
 */
class ServiceModelLister(private val modelsDev: ModelsDev, private val timeoutMs: Int = TIMEOUT_MS) : ModelLister {
    override suspend fun list(protocol: RemoteProtocol, baseUrl: String, apiKey: String?, endpoint: String?): List<ListedModel> = coroutineScope {
        val dev = async { runCatching { modelsDev.list() } }
        val service = runCatching {
            withContext(Dispatchers.IO) {
                val url = endpoint?.trim()?.takeIf { it.isNotEmpty() } ?: defaultEndpoint(protocol, baseUrl)
                get(url, headers(protocol, apiKey.orEmpty()), apiKey.orEmpty(), timeoutMs) { parse(JSONObject(it.readText())) }
            }
        }.onFailure { if (it is CancellationException) throw it }
        val list = dev.await().getOrNull()
        service.getOrNull()?.let { return@coroutineScope merge(it, list, baseUrl) }
        list?.let { fromModelsDev(it, baseUrl) }?.takeIf { it.isNotEmpty() } ?: throw service.exceptionOrNull()!!
    }

    companion object {
        private const val TIMEOUT_MS = 30_000

        /** Where each protocol lists its models; Anthropic and Gemini page their lists, so one large page is asked for. */
        internal fun defaultEndpoint(protocol: RemoteProtocol, baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/') + "/models"
            return when (protocol) {
                RemoteProtocol.ANTHROPIC_MESSAGES -> "$base?limit=1000"
                RemoteProtocol.GEMINI_EMBED_CONTENT -> "$base?pageSize=1000"
                else -> base
            }
        }

        private fun headers(protocol: RemoteProtocol, apiKey: String): Map<String, String> = when (protocol) {
            RemoteProtocol.ANTHROPIC_MESSAGES -> AnthropicMessages.headers(apiKey)
            RemoteProtocol.GEMINI_EMBED_CONTENT -> mapOf("x-goog-api-key" to apiKey)
            else -> mapOf("Authorization" to "Bearer $apiKey")
        }

        /**
         * OpenAI and Anthropic answer `{"data": [{"id", "display_name"?}]}`, Gemini `{"models": [{"name": "models/…",
         * "displayName", "supportedGenerationMethods"}]}`; Gemini's methods say which ones make vectors.
         */
        internal fun parse(json: JSONObject): List<ListedModel> {
            json.optJSONArray("data")?.let { data ->
                return (0 until data.length()).mapNotNull(data::optJSONObject).map {
                    ListedModel(it.optString("id"), it.optString("display_name").ifEmpty { null })
                }.filter { it.id.isNotEmpty() }
            }
            val models = json.optJSONArray("models") ?: return emptyList()
            return (0 until models.length()).mapNotNull(models::optJSONObject).map { model ->
                val methods = model.optJSONArray("supportedGenerationMethods")
                val made = (0 until (methods?.length() ?: 0)).map { methods!!.optString(it) }
                ListedModel(
                    id = model.optString("name").removePrefix("models/"),
                    name = model.optString("displayName").ifEmpty { null },
                    kind = when {
                        made.isEmpty() -> null
                        "embedContent" in made -> ModelKind.EMBEDDING
                        "generateContent" in made -> ModelKind.CHAT
                        else -> ModelKind.OTHER
                    },
                )
            }.filter { it.id.isNotEmpty() }
        }

        /** The service's own IDs, each with what models.dev says of it where it lists it. */
        internal fun merge(service: List<ListedModel>, list: JSONObject?, baseUrl: String): List<ListedModel> = service.map { model ->
            val known = list?.let { ModelsDev.entry(it, baseUrl, model.id) }?.let(ModelsDev::listed)
            // shortcut: a model neither list describes is told apart by "embed" in its ID; unknown names may be wrong.
            ListedModel(
                id = model.id,
                name = model.name ?: known?.name,
                kind = model.kind ?: known?.kind ?: if ("embed" in model.id.lowercase()) ModelKind.EMBEDDING else null,
                readsImages = known?.readsImages,
            )
        }

        internal fun fromModelsDev(list: JSONObject, baseUrl: String): List<ListedModel> {
            val (same, _) = ModelsDev.providers(list, baseUrl)
            return same.flatMap { provider ->
                val models = provider.optJSONObject("models") ?: return@flatMap emptyList()
                models.keys().asSequence().mapNotNull(models::optJSONObject).map(ModelsDev::listed).toList()
            }
        }
    }
}
