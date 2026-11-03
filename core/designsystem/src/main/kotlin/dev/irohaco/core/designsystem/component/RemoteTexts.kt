package dev.irohaco.core.designsystem.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.irohaco.core.contracts.EgressDeniedException
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.RemoteCallException
import dev.irohaco.core.contracts.RemoteFailure
import dev.irohaco.core.designsystem.R

/** What a kind of content is called where the user grants it or agrees to send it (API-007). */
@StringRes
fun EgressPayload.label(): Int = when (this) {
    EgressPayload.QUERY_TEXT -> R.string.egress_query_text
    EgressPayload.IMAGE_PIXELS -> R.string.egress_image_pixels
    EgressPayload.VIDEO_FRAMES -> R.string.egress_video_frames
    EgressPayload.OCR_TEXT -> R.string.egress_ocr_text
    EgressPayload.USER_ANNOTATIONS -> R.string.egress_user_annotations
    // Not offered in R0; named by their codes should one ever be refused.
    EgressPayload.VIDEO_BYTES, EgressPayload.GPS_EXIF -> R.string.egress_query_text
}

/** Why a remote call did not answer, for the user (API-008); null when [cause] did not come from one. */
@Composable
fun remoteFailureText(cause: Throwable): String? = when (cause) {
    is EgressDeniedException -> {
        val ungranted = cause.decision.ungranted
        if (ungranted.isEmpty()) {
            stringResource(R.string.remote_blocked)
        } else {
            stringResource(R.string.remote_denied, ungranted.map { stringResource(it.label()) }.joinToString(" / "))
        }
    }
    is RemoteCallException -> when (val failure = cause.failure) {
        is RemoteFailure.RateLimited -> failure.retryAfterSeconds?.let { stringResource(R.string.remote_rate_limited_after, it.toInt()) }
            ?: stringResource(R.string.remote_rate_limited)
        RemoteFailure.Unauthorized -> stringResource(R.string.remote_unauthorized)
        RemoteFailure.QuotaExceeded -> stringResource(R.string.remote_quota)
        RemoteFailure.Timeout -> stringResource(R.string.remote_timeout)
        is RemoteFailure.Failed -> stringResource(R.string.remote_failed, failure.status)
        RemoteFailure.Unreachable -> stringResource(R.string.remote_unreachable)
    }
    else -> null
}
