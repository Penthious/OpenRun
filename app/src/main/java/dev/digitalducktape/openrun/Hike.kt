package dev.digitalducktape.openrun

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

object HikeLimits { const val MIN_INCLINE = -6.0; const val MAX_INCLINE = 40.0 }

data class SavedHike(val id:String,val name:String,val route:TrailPreview)
data class PendingHike(val hike:SavedHike,val profileId:Long,val maxIncline:Double)

class HikeLibrary(context:Context) {
    private val directory=File(context.filesDir,"hikes").apply { mkdirs() }
    @Synchronized fun import(data:ByteArray,name:String):SavedHike {
        val route=TrailGpx.parse(data.inputStream())
        val id=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val file=AtomicFile(File(directory,"$id.gpx"))
        if(!file.baseFile.exists()) {
            val out=file.startWrite()
            try { out.write(data);file.finishWrite(out) } catch(e:Exception) { file.failWrite(out);throw e }
        }
        val title=name.removeSuffix(".gpx").replace('_',' ').take(160).ifBlank { "Imported hike" }
        val titleFile=File(directory,"$id.txt")
        if(!titleFile.exists() || titleFile.readText()=="Previously imported hike") titleFile.writeText(title)
        return SavedHike(id,titleFile.readText(),route)
    }
    @Synchronized fun list():List<SavedHike> = directory.listFiles().orEmpty().filter { it.extension=="gpx" }.mapNotNull { file ->
        runCatching { SavedHike(file.nameWithoutExtension,File(directory,"${file.nameWithoutExtension}.txt").takeIf { it.exists() }?.readText() ?: "Imported hike",file.inputStream().use(TrailGpx::parse)) }.getOrNull()
    }.sortedBy { it.name }
}

/** Distance-based terrain following; never commands speed. */
class HikeGuide(val selection:PendingHike) {
    private val points=selection.hike.route.points
    val distances=DoubleArray(points.size)
    var status="Ready"; private set
    var complete=false; private set
    private var halted=false
    private var holdUntil=0L
    private var nextChange=0L
    private var lastIncline:Double?=null
    init {
        require(selection.hike.route.hasElevation)
        require(selection.maxIncline.isFinite() && selection.maxIncline in 0.0..HikeLimits.MAX_INCLINE)
        for(i in 1 until points.size) distances[i]=distances[i-1]+TrailPreview(listOf(points[i-1],points[i])).distanceMeters
    }
    val length get()=distances.last()
    private fun elevation(distance:Double):Double {
        val d=distance.coerceIn(0.0,length)
        var i=distances.binarySearch(d)
        if(i>=0) return points[i].elevation!!
        i=(-i-1).coerceIn(1,points.lastIndex)
        val span=distances[i]-distances[i-1]
        val fraction=if(span>0) (d-distances[i-1])/span else 0.0
        return points[i-1].elevation!!+(points[i].elevation!!-points[i-1].elevation!!)*fraction
    }
    fun grade(distance:Double):Double {
        val start=(distance-15).coerceAtLeast(0.0)
        val end=(distance+15).coerceAtMost(length)
        return if(end-start<1) 0.0 else ((elevation(end)-elevation(start))/(end-start)*100).coerceIn(HikeLimits.MIN_INCLINE,selection.maxIncline)
    }
    val startingIncline get()=round(grade(0.0))
    fun manual(target:Target,now:Long) { if(target is Target.Incline) manual(now) }
    fun manual(now:Long) { holdUntil=now+60000; lastIncline=null }
    fun halt() { halted=true; status="Terrain adjustments off · pause and resume to retry" }
    fun resume(now:Long) { halted=false;lastIncline=null;nextChange=now }
    fun confirmed(now:Long,incline:Double?) { lastIncline=incline;nextChange=now }
    fun tick(now:Long,distance:Double,t:Telemetry,fresh:Boolean,paused:Boolean,busy:Boolean):Target.Incline? {
        if(complete) return null
        if(paused || busy) return null
        if(halted) return null
        if(!fresh || t.incline==null || !t.incline.isFinite() || t.incline !in HikeLimits.MIN_INCLINE..HikeLimits.MAX_INCLINE || t.mph==null || !t.mph.isFinite()) { status="Waiting for treadmill data · holding incline";return null }
        if(t.mph<0.2 || t.incline>selection.maxIncline) { halt();return null }
        if(distance>=length) { complete=true;status="Hike complete · stopping belt";return null }
        if(lastIncline!=null && abs(t.incline-lastIncline!!)>0.3) manual(now)
        if(now<holdUntil) { status="Manual override · terrain resumes in ${(holdUntil-now)/1000}s";return null }
        val target=round(grade(distance))
        status="${selection.hike.name} · %.2f / %.2f mi · terrain %.1f%%".format(distance/1609.344,length/1609.344,target)
        if(now<nextChange || abs(target-t.incline)<0.3) return null
        nextChange=now+1000
        return Target.Incline((t.incline+(target-t.incline).coerceIn(-1.0,1.0)).coerceIn(HikeLimits.MIN_INCLINE,selection.maxIncline))
    }
}
