package dev.irohaco.core.application

import dev.irohaco.core.contracts.RemoteFailure

/**
 * Sorts a failed response into what the screens tell the user (API-008). [retryAfter] is the header as
 * sent, in seconds; [body] is the error body, cut short and with [secrets] taken out before it is kept.
 */
fun remoteFailure(status: Int, retryAfter: String?, body: String, secrets: Collection<String> = emptyList()): RemoteFailure {
    // An empty account answers 429 (OpenAI), 400 (Anthropic) or 402; waiting does not help, so it is not a rate limit.
    val quota = QUOTA.containsMatchIn(body)
    return when {
        status == 402 || quota && status in 400..499 -> RemoteFailure.QuotaExceeded
        status == 429 -> RemoteFailure.RateLimited(retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 })
        status == 401 || status == 403 -> RemoteFailure.Unauthorized
        status == 408 || status == 504 -> RemoteFailure.Timeout
        else -> RemoteFailure.Failed(status, redactSecrets(body, secrets).take(MAX_MESSAGE))
    }
}

/** [text] with API keys taken out: the ones the app holds, and anything shaped like a key or its header (PRD §10). */
fun redactSecrets(text: String, secrets: Collection<String> = emptyList()): String {
    val known = secrets.filter { it.length >= MIN_SECRET }.fold(text) { out, secret -> out.replace(secret, MASK) }
    return SHAPES.fold(known) { out, shape -> shape.replace(out) { it.groupValues[1] + MASK } }
}

private val QUOTA = Regex("insufficient_quota|credit balance|billing", RegexOption.IGNORE_CASE)
private val SHAPES = listOf(
    Regex("((?:authorization|x-api-key|x-goog-api-key|api[-_]?key)[\"']?\\s*[:=]\\s*[\"']?(?:bearer\\s+)?)[^\\s\"'&,}]+", RegexOption.IGNORE_CASE),
    Regex("((?:[?&])key=)[^\\s&\"']+"),
    Regex("()\\b(?:sk-ant-|sk-|AIza)[A-Za-z0-9_\\-]{12,}"),
)
private const val MASK = "[hidden]"
private const val MIN_SECRET = 8
private const val MAX_MESSAGE = 300
