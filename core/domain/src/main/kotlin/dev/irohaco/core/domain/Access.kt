package dev.irohaco.core.domain

enum class AccessPurpose { GALLERY_VIEW, AI_INDEX, SEARCH, RAG, REMOTE_SEND, EXIF_WRITE }

enum class AccessDecision { ALLOW, DENY, RECHECK_REQUIRED }

/** What the OS allows right now (USE_CASES UC-ACCESS-01); never stored as an authorization. */
data class LiveMediaAccess(val canRead: Boolean, val canWrite: Boolean, val gpsAllowed: Boolean)
