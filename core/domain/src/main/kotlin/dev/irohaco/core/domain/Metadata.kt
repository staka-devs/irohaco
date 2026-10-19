package dev.irohaco.core.domain

enum class MetadataResolution { KEEP_LOCAL, ACCEPT_REMOTE, MANUAL_MERGE }

data class MetadataTagKey(val assetId: AssetId, val namespace: String, val tagName: String)

/** One value of a tag; exactly one typed column is set per row (schema.sql metadata_override_values). */
sealed interface TypedMetadataValue {
    data class Text(val value: String) : TypedMetadataValue

    data class WholeNumber(val value: Long) : TypedMetadataValue

    data class Decimal(val value: Double) : TypedMetadataValue

    class Raw(val value: ByteArray) : TypedMetadataValue {
        override fun equals(other: Any?): Boolean = other is Raw && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "Raw(${value.size} bytes)"
    }
}

/**
 * An edit of one tag (USE_CASES UC-META-01).
 *
 * @param values null removes the tag (edit_mode REMOVE); a list replaces it (REPLACE). An empty
 *   list is invalid, because REMOVE and having values must not coexist.
 */
data class MetadataEdit(val key: MetadataTagKey, val values: List<TypedMetadataValue>?) {
    init {
        require(values == null || values.isNotEmpty()) { "Use null to remove a tag; an empty list is invalid" }
    }
}
