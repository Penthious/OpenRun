package dev.digitalducktape.openrun
import org.junit.Assert.*
import org.junit.Test

class WorkoutTrackTest {
    @Test fun startAndBoundary() {
        assertEquals(TrackProgress(0,0.0),trackProgress(0.0))
        assertEquals(0,trackProgress(399.9).completedLaps.toInt())
        assertEquals(TrackProgress(1,0.0),trackProgress(400.0))
        assertEquals(TrackProgress(3,25.0),trackProgress(1225.0))
    }
    @Test fun usesFourHundredMetersNotQuarterMiles() {
        val p=trackProgress(1609.344)
        assertEquals(4,p.completedLaps.toInt())
        assertEquals(9.344,p.metersIntoLap,.00001)
        assertEquals(.02336,p.fraction.toDouble(),.00001)
    }
    @Test fun invalidDistanceDoesNotMoveRunner() {
        listOf(-1.0,Double.NaN,Double.POSITIVE_INFINITY).forEach { assertEquals(TrackProgress(0,0.0),trackProgress(it)) }
    }
}
