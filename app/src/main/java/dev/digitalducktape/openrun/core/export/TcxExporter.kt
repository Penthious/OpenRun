package dev.digitalducktape.openrun.core.export

import dev.digitalducktape.openrun.core.data.*
import java.time.Instant

object TcxExporter {
    fun export(ride: Ride, samples: List<RideSample>): String = buildString {
        val start = Instant.ofEpochMilli(ride.startedAt).toString()
        append("""<?xml version="1.0" encoding="UTF-8"?><TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2"><Activities><Activity Sport="Running"><Id>$start</Id><Lap StartTime="$start"><TotalTimeSeconds>${ride.durationSec}</TotalTimeSeconds><DistanceMeters>${ride.distanceMeters}</DistanceMeters><Calories>0</Calories><Intensity>Active</Intensity><TriggerMethod>Manual</TriggerMethod>""")
        if (samples.isNotEmpty()) {
            append("<Track>")
            samples.forEach { s ->
                append("<Trackpoint><Time>${Instant.ofEpochMilli(s.timestamp)}</Time><DistanceMeters>${s.distanceMeters}</DistanceMeters>")
                s.heartRate?.let { append("<HeartRateBpm><Value>$it</Value></HeartRateBpm>") }
                s.speedMps?.let { append("<Extensions><ns3:TPX><ns3:Speed>$it</ns3:Speed></ns3:TPX></Extensions>") }
                append("</Trackpoint>")
            }
            append("</Track>")
        }
        append("</Lap><Notes>OpenRun indoor workout. Estimated climbing: ${ride.ascentMeters.toInt()} m. Estimated descent: ${ride.descentMeters?.toInt()?.toString() ?: "unavailable"} m.</Notes></Activity></Activities></TrainingCenterDatabase>")
    }
}
