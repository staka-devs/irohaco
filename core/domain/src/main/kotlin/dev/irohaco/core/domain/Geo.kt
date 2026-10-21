package dev.irohaco.core.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** A rectangle on the map, edges included (PRD GAL-017). Never crosses the antimeridian here. */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    operator fun contains(point: GeoPoint): Boolean =
        point.latitude in south..north && point.longitude in west..east
}

/**
 * Nearby points shown as one marker with a count; tapping it opens the photos in [bounds].
 * [first] is the index in the input of the group's first point, whose photo the marker shows (M1-29).
 */
data class GeoCluster(val center: GeoPoint, val count: Int, val bounds: GeoBounds, val first: Int = 0)

/**
 * Groups points that fall in the same square of the map at [zoom] (Web Mercator, as both map
 * libraries draw). [cellsPerTile] squares across one 256-pixel tile make a square about
 * 256 / [cellsPerTile] pixels wide on screen. The map library is not asked to cluster, so both
 * maps show the same groups (ADR-0011).
 */
fun clusterPoints(points: List<GeoPoint>, zoom: Double, cellsPerTile: Int = 4): List<GeoCluster> {
    val scale = 2.0.pow(zoom) * cellsPerTile
    return points.withIndex().groupBy { (_, p) ->
        val x = floor((p.longitude + 180.0) / 360.0 * scale).toLong()
        val latRad = p.latitude.coerceIn(-85.0511, 85.0511) * PI / 180.0
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * scale).toLong()
        x to y
    }.values.map { indexed ->
        val group = indexed.map { it.value }
        GeoCluster(
            center = GeoPoint(group.sumOf { it.latitude } / group.size, group.sumOf { it.longitude } / group.size),
            count = group.size,
            bounds = GeoBounds(
                south = group.minOf { it.latitude },
                west = group.minOf { it.longitude },
                north = group.maxOf { it.latitude },
                east = group.maxOf { it.longitude },
            ),
            first = indexed.first().index,
        )
    }
}
