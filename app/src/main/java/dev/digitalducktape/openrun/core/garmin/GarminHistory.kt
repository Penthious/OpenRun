package dev.digitalducktape.openrun.core.garmin

import dev.digitalducktape.openrun.*
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.ZoneOffset

data class HistoryActivity(val id:Long,val startedAt:Long,val treadmill:Boolean)
object GarminHistoryParser {
    private fun JsonObject.text(key:String)=(get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.num(key:String)=(get(key) as? JsonPrimitive)?.doubleOrNull
    fun activities(body:String,now:Long):List<HistoryActivity> {
        val array=Json.parseToJsonElement(body) as? JsonArray ?: throw GarminFailure("Garmin history response was unreadable.")
        return array.mapNotNull { element ->
            val o=element as? JsonObject ?: return@mapNotNull null
            val type=(o["activityType"] as? JsonObject)?.text("typeKey")
            if(type !in setOf("running","treadmill_running")) return@mapNotNull null
            val id=(o["activityId"] as? JsonPrimitive)?.longOrNull?.takeIf { it>0 } ?: return@mapNotNull null
            val at=runCatching { LocalDateTime.parse(o.text("startTimeGMT").replace(' ','T')).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            if(at !in (now-AdaptivePace.AGE_MS)..now || (o.num("duration") ?: 0.0)<600) return@mapNotNull null
            HistoryActivity(id,at,type=="treadmill_running")
        }.distinctBy { it.id }.sortedByDescending { it.startedAt }.take(12)
    }
    fun segments(body:String,activity:HistoryActivity):List<PaceSegment> {
        val o=Json.parseToJsonElement(body).jsonObject
        val descriptors=(o["metricDescriptors"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        fun column(key:String,units:Set<String>):Int? = descriptors.firstOrNull { it.text("key")==key &&
            (it["unit"] as? JsonObject)?.text("key") in units }?.num("metricsIndex")?.toInt()
        val timestamp=column("directTimestamp",setOf("gmt")) ?: return emptyList()
        val heart=column("directHeartRate",setOf("bpm")) ?: return emptyList()
        val speedDescriptor=descriptors.firstOrNull { it.text("key")=="directSpeed" } ?: return emptyList()
        val speedUnit=(speedDescriptor["unit"] as? JsonObject)?.text("key")
        val toMph=when(speedUnit) { "mps" -> 1/.44704; "mph" -> 1.0; "kph" -> 1/1.609344; else -> return emptyList() }
        val speed=speedDescriptor.num("metricsIndex")?.toInt() ?: return emptyList()
        val grade=column("directGrade",setOf("percent","percentage"))
        val elevation=column("directElevation",setOf("meter")) ?: column("directCorrectedElevation",setOf("meter"))
        val distance=column("sumDistance",setOf("meter"))
        val rows=(o["activityDetailMetrics"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("metrics") as? JsonArray }.orEmpty()
        fun value(row:JsonArray,index:Int?)=index?.let { (row.getOrNull(it) as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.isFinite() }
        var old:JsonArray?=null
        val points=rows.mapNotNull { row ->
            val at=value(row,timestamp)?.toLong() ?: return@mapNotNull null
            val previous=old; old=row
            val meters=previous?.let { p -> value(row,distance)?.minus(value(p,distance) ?: return@let null) }
            val derived=if(meters!=null && meters>1) previous?.let { p -> value(row,elevation)?.minus(value(p,elevation) ?: return@let null)?.div(meters)?.times(100) } else null
            val slope=value(row,grade) ?: if(activity.treadmill) null else derived
            // Treadmill records without grade cannot establish comparable flat-running pace.
            PacePoint(at,value(row,speed)?.times(toMph),value(row,heart)?.toInt(),slope,at>=activity.startedAt+300_000)
        }
        return AdaptivePace.segments("garmin:${activity.id}",activity.treadmill,points)
    }
}
