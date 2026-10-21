package dev.irohaco.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    private val tokyo = GeoPoint(35.6812, 139.7671)
    private val tokyoNear = GeoPoint(35.6815, 139.7675)
    private val osaka = GeoPoint(34.7025, 135.4959)

    @Test
    fun `nearby points become one counted group, far ones stay apart`() {
        val clusters = clusterPoints(listOf(tokyo, tokyoNear, osaka), zoom = 10.0)
        assertEquals(listOf(1, 2), clusters.map { it.count }.sorted())
        val pair = clusters.single { it.count == 2 }
        assertTrue(tokyo in pair.bounds && tokyoNear in pair.bounds)
        assertEquals(GeoBounds(35.6812, 139.7671, 35.6815, 139.7675), pair.bounds)
    }

    @Test
    fun `zooming out merges what zooming in keeps apart`() {
        assertEquals(1, clusterPoints(listOf(tokyo, osaka), zoom = 2.0).size)
        assertEquals(2, clusterPoints(listOf(tokyo, osaka), zoom = 8.0).size)
        assertEquals(2, clusterPoints(listOf(tokyo, tokyoNear), zoom = 20.0).size)
        // Each marker shows the photo of its group's first point (the newest, as the store lists them).
        assertEquals(listOf(0, 1), clusterPoints(listOf(osaka, tokyo, tokyoNear), zoom = 10.0).map { it.first }.sorted())
    }

    @Test
    fun `bounds include their edges`() {
        val bounds = GeoBounds(0.0, 0.0, 1.0, 1.0)
        assertTrue(GeoPoint(1.0, 0.0) in bounds)
        assertEquals(false, GeoPoint(1.1, 0.0) in bounds)
    }
}
