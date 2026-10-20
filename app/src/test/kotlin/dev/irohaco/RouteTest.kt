package dev.irohaco

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteTest {
    @Test
    fun `viewer routes keep the asset id and the optional start position`() {
        assertEquals("a-1" to null, Route.parseViewer(Route.viewer("a-1")))
        assertEquals("a-1" to 42_000L, Route.parseViewer(Route.playVideoAt("a-1", 42_000)))
    }
}
