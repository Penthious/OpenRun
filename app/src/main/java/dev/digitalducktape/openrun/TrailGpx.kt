package dev.digitalducktape.openrun

import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import kotlin.math.*

data class TrailPoint(val latitude: Double, val longitude: Double, val elevation: Double?)
data class TrailPreview(val points: List<TrailPoint>) {
    val distanceMeters: Double get() = points.zipWithNext().sumOf { (a,b) ->
        val lat = Math.toRadians(b.latitude-a.latitude)
        val lon = Math.toRadians(b.longitude-a.longitude)
        val h = sin(lat/2).pow(2)+cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(lon/2).pow(2)
        6371000 * 2 * asin(sqrt(h.coerceIn(0.0,1.0)))
    }
    val hasElevation get() = points.all { it.elevation != null }
}

/** Preview only: no route data is sent to treadmill controls. */
object TrailGpx {
    const val MAX_BYTES = 4 * 1024 * 1024
    fun parse(input: InputStream): TrailPreview {
        val bytes = input.readBytesLimited()
        // Android Expat does not implement Xerces' disallow-doctype-decl feature.
        // Decode once, reject DTDs before parsing, and pass the same characters to SAX.
        val xml = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            .toString().removePrefix("\uFEFF")
        require(!xml.contains('\u0000')) { "Export the route as UTF-8 GPX." }
        require(!xml.contains("<!DOCTYPE", ignoreCase=true)) { "GPX files with DTD declarations are not supported." }
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val points = mutableListOf<TrailPoint>()
        var current: TrailPoint? = null
        var inElevation = false
        var elevation = StringBuilder()
        var rootSeen = false
        var segments = 0
        factory.newSAXParser().parse(InputSource(xml.reader()), object: DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
                throw IllegalArgumentException("External XML references are not supported.")
            }
            override fun startElement(uri: String?, localName: String, qName: String?, attrs: Attributes) {
                if (!rootSeen) { require(localName == "gpx") { "Choose a GPX route file." }; rootSeen = true }
                if (localName == "trkseg" || localName == "rte") {
                    segments++
                    require(segments <= 1) { "Choose a single continuous route for this preview." }
                }
                if (localName == "trkpt" || localName == "rtept") {
                    require(points.size < 50000) { "Route has too many points." }
                    val lat = attrs.getValue("lat")?.toDoubleOrNull()
                    val lon = attrs.getValue("lon")?.toDoubleOrNull()
                    require(lat != null && lat.isFinite() && lat in -90.0..90.0 && lon != null && lon.isFinite() && lon in -180.0..180.0) { "Route contains invalid coordinates." }
                    current = TrailPoint(lat,lon,null)
                }
                if (localName == "ele" && current != null) { inElevation = true; elevation = StringBuilder() }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if(inElevation) elevation.append(ch,start,length) }
            override fun endElement(uri: String?, localName: String, qName: String?) {
                if(localName == "ele" && inElevation) {
                    val value = elevation.toString().trim().toDoubleOrNull()
                    require(value != null && value.isFinite() && value in -500.0..10000.0) { "Route contains invalid elevation." }
                    current = current?.copy(elevation=value); inElevation = false
                }
                if(localName == "trkpt" || localName == "rtept") { current?.let { points.add(it) }; current = null }
            }
        })
        require(points.size >= 2) { "GPX must contain at least two route points." }
        return TrailPreview(points).also { require(it.distanceMeters > 0) { "Route has no distance." } }
    }
    fun InputStream.readBytesLimited(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while(true) {
            val count = read(buffer)
            if(count < 0) break
            require(out.size()+count <= MAX_BYTES) { "Choose a GPX smaller than 4 MB." }
            out.write(buffer,0,count)
        }
        return out.toByteArray()
    }
}
