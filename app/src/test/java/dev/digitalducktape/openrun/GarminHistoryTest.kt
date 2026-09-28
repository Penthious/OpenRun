package dev.digitalducktape.openrun.core.garmin
import org.junit.Assert.*
import org.junit.Test

class GarminHistoryTest {
    private val start=1_800_000_000_000L
    @Test fun `descriptor units are honored and unknown speed units never inferred`() {
        val rows=(0..180).joinToString(",") { "{\"metrics\":[${start+300000+it*1000},2.2352,150,1]}" }
        val body="""{"metricDescriptors":[
            {"key":"directTimestamp","metricsIndex":0,"unit":{"key":"gmt"}},
            {"key":"directSpeed","metricsIndex":1,"unit":{"key":"mps"}},
            {"key":"directHeartRate","metricsIndex":2,"unit":{"key":"bpm"}},
            {"key":"directGrade","metricsIndex":3,"unit":{"key":"percent"}}],"activityDetailMetrics":[$rows]}"""
        val result=GarminHistoryParser.segments(body,HistoryActivity(1,start,true))
        assertEquals(2,result.size);assertEquals(5.0,result.first().mph,.001)
        assertTrue(GarminHistoryParser.segments(body.replace("mps","unknown"),HistoryActivity(1,start,true)).isEmpty())
        assertTrue(GarminHistoryParser.segments(body.replace("directGrade","unknown"),HistoryActivity(1,start,true)).isEmpty())
    }
    @Test fun `only recent running sessions are requested`() {
        val body="""[
            {"activityId":1,"startTimeGMT":"2026-09-20 10:00:00","duration":1500,"activityType":{"typeKey":"running"}},
            {"activityId":2,"startTimeGMT":"2026-09-20 10:00:00","duration":1500,"activityType":{"typeKey":"cycling"}},
            {"activityId":3,"startTimeGMT":"2025-09-20 10:00:00","duration":1500,"activityType":{"typeKey":"treadmill_running"}}
        ]"""
        assertEquals(listOf(1L),GarminHistoryParser.activities(body,java.time.Instant.parse("2026-09-27T12:00:00Z").toEpochMilli()).map { it.id })
    }
}
