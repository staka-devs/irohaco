package dev.irohaco.core.contracts

import dev.irohaco.core.domain.AssetId
import kotlin.test.Test
import kotlin.test.assertFailsWith

class IdentityCandidateViewTest {
    @Test
    fun `confidence must be between 0 and 1`() {
        assertFailsWith<IllegalArgumentException> { IdentityCandidateView(AssetId("a1"), 1.1, emptyList()) }
        assertFailsWith<IllegalArgumentException> { IdentityCandidateView(AssetId("a1"), -0.1, emptyList()) }
        IdentityCandidateView(AssetId("a1"), 0.0, emptyList())
        IdentityCandidateView(AssetId("a1"), 1.0, emptyList())
    }
}
