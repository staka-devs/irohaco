package dev.irohaco.core.application

import dev.irohaco.core.contracts.EmbeddingSpace
import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.SpaceState

/** A space whose signature is made from its settings and the model file of [digest], as indexing makes it. */
internal fun testSpace(
    id: String,
    state: SpaceState,
    isDefault: Boolean = false,
    prefix: String? = "q: ",
    digest: String = "d",
    preprocessing: String = "p",
): EmbeddingSpace {
    val spec = EmbeddingSpaceSpec("m", digest, 2, "L2", prefix, null, preprocessing)
    return EmbeddingSpace(id, "Model", state, ImageSpaces.signature(spec), isDefault, "m", 2, "L2", prefix, null, preprocessing)
}
