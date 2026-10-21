package dev.irohaco.infrastructure.media

import android.content.Context
import android.location.Address
import android.location.Geocoder
import dev.irohaco.core.contracts.PlaceNames
import dev.irohaco.core.domain.GeoPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The platform Geocoder (ADR-0011). On many devices it asks a server, so the caller checks consent
 * first. Returns the town and the region, such as "千代田区, 東京都"; null when there is no answer.
 */
class AndroidPlaceNames(private val context: Context) : PlaceNames {
    override suspend fun nameOf(point: GeoPoint): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        return suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(
                point.latitude,
                point.longitude,
                1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        continuation.resume(addresses.firstOrNull()?.let(::placeOf))
                    }

                    override fun onError(errorMessage: String?) {
                        continuation.resume(null)
                    }
                },
            )
        }
    }

    private fun placeOf(address: Address): String? =
        listOfNotNull(address.locality ?: address.subAdminArea, address.adminArea).distinct().joinToString(", ").ifEmpty { null }
}
