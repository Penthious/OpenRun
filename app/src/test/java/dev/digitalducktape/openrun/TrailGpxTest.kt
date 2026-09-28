package dev.digitalducktape.openrun

import org.junit.Assert.*
import org.junit.Test

class TrailGpxTest {
    private fun parse(body:String)=TrailGpx.parse(body.byteInputStream())
    private fun track(ele:String="<ele>100</ele>")="""<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg><trkpt lat="0" lon="0">$ele</trkpt><trkpt lat="0" lon="0.01">$ele</trkpt></trkseg></trk></gpx>"""
    @Test fun readsUtf8Bom() { assertTrue(parse("\uFEFF"+track()).hasElevation) }
    @Test(expected=Exception::class) fun rejectsUtf16Doctype() {
        TrailGpx.parse(("<!DOCTYPE gpx [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"+track()).toByteArray(Charsets.UTF_16).inputStream())
    }
    @Test(expected=Exception::class) fun rejectsMalformedUtf8() { TrailGpx.parse(byteArrayOf(0xC3.toByte(),0x28).inputStream()) }
    @Test fun readsNamespacedTrackAndDistance() { val route=parse(track()); assertEquals(1111.95,route.distanceMeters,0.1); assertTrue(route.hasElevation) }
    @Test fun missingElevationIsPreviewOnly() { assertFalse(parse(track("" )).hasElevation) }
    @Test(expected=Exception::class) fun rejectsDoctype() { parse("<!DOCTYPE gpx [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"+track()) }
    @Test(expected=IllegalArgumentException::class) fun rejectsNonFiniteCoordinates() { parse(track().replace("lat=\"0\"","lat=\"NaN\"")) }
    @Test(expected=IllegalArgumentException::class) fun rejectsOversizedFiles() { parse(" ".repeat(TrailGpx.MAX_BYTES+1)) }
    @Test(expected=IllegalArgumentException::class) fun rejectsDisconnectedSegments() { parse(track().replace("</trkseg>","</trkseg><trkseg></trkseg>")) }
    @Test fun readsRoutePoints() { assertEquals(2,parse(track().replace("<trk><trkseg>","<rte>").replace("</trkseg></trk>","</rte>").replace("trkpt","rtept")).points.size) }
}
