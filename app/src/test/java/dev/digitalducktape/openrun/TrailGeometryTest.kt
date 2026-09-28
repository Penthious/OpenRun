package dev.digitalducktape.openrun
import org.junit.Assert.*
import org.junit.Test
class TrailGeometryTest {
    private val geometry=TrailGeometry(TrailPreview(listOf(TrailPoint(0.0,0.0,0.0),TrailPoint(0.0,0.01,0.0),TrailPoint(0.01,0.01,0.0))))
    @Test fun interpolatesAlongDistanceNotPointCount() { val p=geometry.position(geometry.distances[1]/2).second;assertEquals(0.005,p.longitude,0.000001);assertEquals(0.0,p.latitude,0.0) }
    @Test fun clampsEnds() { assertEquals(0.0,geometry.position(-50.0).second.longitude,0.0);assertEquals(0.01,geometry.position(1e9).second.latitude,0.0) }
}
