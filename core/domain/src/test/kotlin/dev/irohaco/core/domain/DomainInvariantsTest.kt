package dev.irohaco.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DomainInvariantsTest {
    private val key = MetadataTagKey(AssetId("a1"), "EXIF", "Description")

    @Test
    fun `metadata edit rejects an empty list`() {
        assertFailsWith<IllegalArgumentException> { MetadataEdit(key, emptyList()) }
    }

    @Test
    fun `metadata edit allows remove and replace`() {
        MetadataEdit(key, null)
        MetadataEdit(key, listOf(TypedMetadataValue.Text("beach")))
    }

    @Test
    fun `raw values compare by content`() {
        assertEquals(TypedMetadataValue.Raw(byteArrayOf(1, 2)), TypedMetadataValue.Raw(byteArrayOf(1, 2)))
        assertEquals(TypedMetadataValue.Raw(byteArrayOf(1, 2)).hashCode(), TypedMetadataValue.Raw(byteArrayOf(1, 2)).hashCode())
        assertNotEquals(TypedMetadataValue.Raw(byteArrayOf(1, 2)), TypedMetadataValue.Raw(byteArrayOf(2, 1)))
    }

    @Test
    fun `link existing requires a selected asset`() {
        assertFailsWith<IllegalArgumentException> { decision(IdentityAction.LINK_EXISTING, null) }
        decision(IdentityAction.LINK_EXISTING, AssetId("a1"))
    }

    @Test
    fun `new asset and ignore must not name an asset`() {
        assertFailsWith<IllegalArgumentException> { decision(IdentityAction.NEW_ASSET, AssetId("a1")) }
        assertFailsWith<IllegalArgumentException> { decision(IdentityAction.IGNORE, AssetId("a1")) }
        decision(IdentityAction.NEW_ASSET, null)
        decision(IdentityAction.IGNORE, null)
    }

    @Test
    fun `restore preview rejects negative counts`() {
        assertFailsWith<IllegalArgumentException> { RestorePreview(RestoreId("r1"), -1, 0, 0, 0) }
        RestorePreview(RestoreId("r1"), 0, 0, 0, 0)
    }

    private fun decision(action: IdentityAction, asset: AssetId?) =
        IdentityDecision(DiscoveryId("d1"), action, asset, IdentityDecisionOrigin.USER)
}
