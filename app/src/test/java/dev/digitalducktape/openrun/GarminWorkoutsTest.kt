package dev.digitalducktape.openrun.core.garmin

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GarminWorkoutsTest {
    @Test fun `explicit pace target converts meters per second without using estimated distance`() {
        val body="""{"workoutName":"Base","sportType":{"sportTypeKey":"running"},"estimatedDistanceInMeters":3862,
            "workoutSegments":[{"workoutSteps":[{"stepType":{"stepTypeKey":"interval"},
            "endCondition":{"conditionTypeKey":"time"},"endConditionValue":1440,
            "targetType":{"workoutTargetTypeKey":"pace.zone"},"targetValueOne":2.68224,"targetValueTwo":2.68224}]}]}"""
        val step=GarminWorkoutParser.preview(body).executableSteps.single()
        assertEquals(6.0,step.paceLowMph!!,.001)
        assertNull(step.hrLow)
        val heart=body.replace("pace.zone","heart.rate.zone").replace("2.68224","150")
        val hrStep=GarminWorkoutParser.preview(heart).executableSteps.single()
        assertNull(hrStep.paceLowMph)
        assertNull(hrStep.startMph)
    }

    @Test fun `calendar request uses runner date instead of device month`() = kotlinx.coroutines.runBlocking {
        val paths=mutableListOf<String>()
        val api=GarminApi(object:GarminTransport {
            override fun request(url:String,headers:Map<String,String>,body:ByteArray):GarminResponse=error("Read only")
            override fun get(url:String,headers:Map<String,String>):GarminResponse {
                paths+=url
                return GarminResponse(200,"""{"calendarItems":[{"itemType":"workout","id":1,"workoutId":2,"date":"2026-09-30","title":"Base"}]}""")
            }
        },{java.time.Instant.parse("2026-10-01T00:30:00Z").toEpochMilli()})
        val result=api.scheduledWorkouts(GarminTokens("test","test","test",Long.MAX_VALUE),LocalDate.of(2026,9,30))
        assertTrue(paths.first().endsWith("/year/2026/month/8"))
        assertEquals("2026-09-30",result.single().date)
    }

    @Test fun `FIT multipart preserves binary bytes and uses FIT endpoint`() = kotlinx.coroutines.runBlocking {
        val bytes=byteArrayOf(0,127,-128,-1,13,10)
        val api=GarminApi(object:GarminTransport {
            override fun request(url:String,headers:Map<String,String>,body:ByteArray):GarminResponse {
                assertTrue(url.endsWith("/upload-service/upload/fit"))
                val boundary=headers.getValue("Content-Type").substringAfter("boundary=")
                val prefix="--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"openrun.fit\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray()
                assertArrayEquals(prefix+bytes+"\r\n--$boundary--\r\n".toByteArray(),body)
                return GarminResponse(409,"")
            }
        })
        assertTrue(api.uploadFit(GarminTokens("test","test","test",Long.MAX_VALUE),bytes))
    }

    @Test fun `calendar filters past workouts and activities without confusing schedule and workout IDs`() {
        val result = GarminWorkoutParser.calendar("""{"calendarItems":[
          {"id":1,"itemType":"workout","date":"2026-09-26","title":"Past"},
          {"id":2,"itemType":"activity","date":"2026-09-27"},
          {"id":3,"workoutId":99,"itemType":"workout","date":"2026-09-27","title":"Coach run"},
          {"id":4,"itemType":"workout","date":"2026-10-02","title":"Next month"}
        ]}""", LocalDate.of(2026,9,27))
        assertEquals(listOf(3L,4L),result.map { it.scheduleId })
        assertEquals(99L,result.first().workoutId)
        assertNull(result.last().workoutId)
        assertEquals(99L,GarminWorkoutParser.scheduledId("""{"workoutId":99}"""))
    }
    @Test fun `adaptive sessions use UUID even without numeric schedule id`() {
        val result=GarminWorkoutParser.calendar("""{"calendarItems":[
          {"itemType":"fbtAdaptiveWorkout","workoutUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","date":"2026-09-28","title":"Base"},
          {"itemType":"fbtAdaptiveWorkout","workoutUuid":"ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee","date":"2026-09-29","title":"Tempo"},
          {"itemType":"fbtAdaptiveWorkout","workoutUuid":"../../other","date":"2026-09-28"},
          {"itemType":"trainingPlan","id":1,"date":"2026-09-28"}
        ]}""",LocalDate.of(2026,9,27))
        assertEquals(listOf("Base","Tempo"),result.map { it.name })
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",result.first().adaptiveUuid)
    }

    @Test fun `adaptive import uses read only endpoint and preserves multiple sessions per day`() = kotlinx.coroutines.runBlocking {
        val paths=mutableListOf<String>()
        val transport=object:GarminTransport {
            override fun request(url:String,headers:Map<String,String>,body:ByteArray):GarminResponse = error("No POST during import")
            override fun get(url:String,headers:Map<String,String>):GarminResponse {
                paths+=url
                if(url.contains("calendar-service")) return GarminResponse(200,"""{"calendarItems":[
                  {"itemType":"fbtAdaptiveWorkout","workoutUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","date":"2026-09-28","title":"Base"},
                  {"itemType":"fbtAdaptiveWorkout","workoutUuid":"ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee","date":"2026-09-28","title":"Tempo"}
                ]}""")
                return GarminResponse(200,"""{"workoutName":"Base","workoutSegments":[{"workoutSteps":[{"stepType":{"stepTypeKey":"interval"},"endCondition":{"conditionTypeKey":"time"},"endConditionValue":1440,"targetType":{"workoutTargetTypeKey":"heart.rate.zone"},"targetValueOne":140,"targetValueTwo":168}]}]}""")
            }
        }
        val api=GarminApi(transport,{java.time.Instant.parse("2026-09-27T12:00:00Z").toEpochMilli()})
        val tokens=GarminTokens("test","test","test",Long.MAX_VALUE)
        val workouts=api.scheduledWorkouts(tokens)
        assertEquals(2,workouts.size)
        val preview=api.workoutPreview(tokens,workouts.first())
        assertEquals(workouts.first(),preview.source)
        assertTrue(preview.steps.single().contains("24 min · 140–168 bpm"))
        assertTrue(paths.last().endsWith("/workout-service/fbt-adaptive/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
    }

    @Test fun `preview preserves repeats and distinguishes HR zones from BPM`() {
        val preview=GarminWorkoutParser.preview("""{"workoutName":"Coach run","sportType":{"sportTypeKey":"running"},"workoutSegments":[{"workoutSteps":[
          {"type":"RepeatGroupDTO","stepOrder":1,"numberOfIterations":3,"workoutSteps":[
            {"stepOrder":1,"stepType":{"stepTypeKey":"interval"},"endCondition":{"conditionTypeKey":"time"},"endConditionValue":300,"targetType":{"workoutTargetTypeKey":"heart.rate.zone"},"targetValueOne":120,"targetValueTwo":140},
            {"stepOrder":2,"endCondition":{"conditionTypeKey":"lap.button"},"targetType":{"workoutTargetTypeKey":"heart.rate.zone"},"zoneNumber":2}
          ]}
        ]}]}""")
        assertEquals(3,preview.steps.size)
        assertTrue(preview.steps[0].contains("Repeat 3 times"))
        assertTrue(preview.steps[1].contains("5 min · 120–140 bpm"))
        assertTrue(preview.steps[2].contains("HR zone 2"))
    }
    @Test fun `executable repeat omits last recovery and rejects unknown targets`() {
        val body="""{"workoutName":"Tempo","sportType":{"sportTypeKey":"running"},"workoutSegments":[{"workoutSteps":[
          {"stepOrder":1,"endCondition":{"conditionTypeKey":"time"},"endConditionValue":600,"targetType":{"workoutTargetTypeKey":"heart.rate.zone"},"targetValueOne":140,"targetValueTwo":168},
          {"stepOrder":2,"type":"RepeatGroupDTO","numberOfIterations":3,"skipLastRestStep":true,"endCondition":{"conditionTypeKey":"iterations"},"workoutSteps":[
            {"stepOrder":1,"stepType":{"stepTypeKey":"interval"},"endCondition":{"conditionTypeKey":"time"},"endConditionValue":300,"targetType":{"workoutTargetTypeKey":"heart.rate.zone"},"targetValueOne":168,"targetValueTwo":178},
            {"stepOrder":2,"stepType":{"stepTypeKey":"recovery"},"endCondition":{"conditionTypeKey":"time"},"endConditionValue":120,"targetType":{"workoutTargetTypeKey":"no.target"}}
          ]},
          {"stepOrder":3,"endCondition":{"conditionTypeKey":"time"},"endConditionValue":300,"targetType":{"workoutTargetTypeKey":"heart.rate.zone"},"targetValueOne":140,"targetValueTwo":168}
        ]}]}"""
        val parsed=GarminWorkoutParser.preview(body)
        assertNull(parsed.executionIssue)
        assertEquals(2040,parsed.executableSteps.sumOf { it.seconds })
        assertEquals(7,parsed.executableSteps.size)
        val unsupported=GarminWorkoutParser.preview(body.replace("heart.rate.zone","pace.zone"))
        assertNotNull(unsupported.executionIssue)
        assertTrue(unsupported.executableSteps.isEmpty())
    }

    @Test(expected=GarminFailure::class) fun `malformed calendar does not appear as empty success`() {
        GarminWorkoutParser.calendar("{}",LocalDate.now())
    }
    @Test(expected=GarminFailure::class) fun `missing steps are explicit failure`() {
        GarminWorkoutParser.preview("{}")
    }
}
