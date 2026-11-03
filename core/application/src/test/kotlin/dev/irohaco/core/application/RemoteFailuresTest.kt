package dev.irohaco.core.application

import dev.irohaco.core.contracts.RemoteFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Failed responses are told apart for the user, and no key reaches a message or a log (API-008, PRD §10). */
class RemoteFailuresTest {
    @Test
    fun `responses are sorted by what the user can do about them`() {
        assertEquals(RemoteFailure.RateLimited(20), remoteFailure(429, "20", "{}"))
        assertEquals(RemoteFailure.RateLimited(null), remoteFailure(429, "Wed, 21 Oct 2026 07:28:00 GMT", "{}"))
        assertEquals(RemoteFailure.QuotaExceeded, remoteFailure(429, "1", """{"error":{"code":"insufficient_quota"}}"""))
        assertEquals(RemoteFailure.QuotaExceeded, remoteFailure(400, null, "Your credit balance is too low"))
        assertEquals(RemoteFailure.QuotaExceeded, remoteFailure(402, null, ""))
        assertEquals(RemoteFailure.Unauthorized, remoteFailure(401, null, "{}"))
        assertEquals(RemoteFailure.Unauthorized, remoteFailure(403, null, "{}"))
        assertEquals(RemoteFailure.Timeout, remoteFailure(504, null, ""))
        assertEquals(RemoteFailure.Failed(500, "overloaded"), remoteFailure(500, null, "overloaded"))
    }

    @Test
    fun `an error body that repeats the key keeps it out of the message`() {
        val failure = remoteFailure(400, null, "bad request for key my-own-key-123", secrets = listOf("my-own-key-123")) as RemoteFailure.Failed
        assertEquals("bad request for key [hidden]", failure.message)
    }

    @Test
    fun `anything shaped like a key or its header is hidden`() {
        val text = redactSecrets(
            """Authorization: Bearer abc.def x-api-key: sk-ant-api03-AAAAAAAAAAAAAAAA "api_key":"zzz" GET /v1beta/models?key=AIzaSyBBBBBBBBBBBBBBBB&alt=sse""",
        )
        assertEquals("""Authorization: Bearer [hidden] x-api-key: [hidden] "api_key":"[hidden]" GET /v1beta/models?key=[hidden]&alt=sse""", text)
        assertFalse("sk-proj-CCCCCCCCCCCCCCCCCC" in redactSecrets("used sk-proj-CCCCCCCCCCCCCCCCCC here"))
    }
}
