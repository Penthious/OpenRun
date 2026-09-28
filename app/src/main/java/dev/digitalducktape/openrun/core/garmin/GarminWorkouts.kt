package dev.digitalducktape.openrun.core.garmin

import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.json.*

@kotlinx.serialization.Serializable
data class GarminPlannedWorkout(val scheduleId: Long, val workoutId: Long?, val date: String, val name: String, val adaptiveUuid: String? = null)
@kotlinx.serialization.Serializable
data class GarminWorkoutPreview(val name: String, val sport: String, val steps: List<String>, val executableSteps: List<dev.digitalducktape.openrun.PlannedStep> = emptyList(), val executionIssue: String? = null, val source: GarminPlannedWorkout? = null)

/** Read-only previews. Unknown targets are labeled explicitly, never turned into machine commands. */
object GarminWorkoutParser {
    private fun objectFrom(body: String): JsonObject = try { Json.parseToJsonElement(body).jsonObject }
        catch (_: Exception) { throw GarminFailure("Garmin returned an unreadable workout response.") }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.id(key: String) = (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
    private fun JsonObject.key(field: String, key: String) = (get(field) as? JsonObject)?.text(key).orEmpty()
    private fun JsonObject.objects(key: String) = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun n(value: Double) = String.format(Locale.US, "%.1f", value).removeSuffix(".0")

    fun calendar(body: String, today: LocalDate): List<GarminPlannedWorkout> {
        val root = objectFrom(body)
        if (root["calendarItems"] !is JsonArray) throw GarminFailure("Garmin did not return its calendar. Try again later.")
        return root.objects("calendarItems").mapNotNull { item ->
            val adaptive = item.text("itemType") == "fbtAdaptiveWorkout"
            if (!adaptive && item.text("itemType") != "workout") return@mapNotNull null
            val date = runCatching { LocalDate.parse(item.text("date").take(10)) }.getOrNull() ?: return@mapNotNull null
            val uuid = if (adaptive) item.text("workoutUuid").takeIf {
                it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            } ?: return@mapNotNull null else null
            val id = if (adaptive) 0L else item.id("id") ?: return@mapNotNull null
            if (date < today) return@mapNotNull null
            GarminPlannedWorkout(id, item.id("workoutId"), date.toString(), item.text("title").ifBlank { "Scheduled workout" }, uuid)
        }
    }

    fun scheduledId(body: String): Long {
        val root = objectFrom(body)
        return root.id("workoutId") ?: (root["workout"] as? JsonObject)?.id("workoutId")
            ?: throw GarminFailure("Garmin listed this session but did not expose a workout to preview.")
    }

    fun preview(body: String): GarminWorkoutPreview {
        val root = objectFrom(body)
        val lines = mutableListOf<String>()
        fun steps(items: List<JsonObject>, depth: Int) {
            if (depth > 6 || lines.size > 200) throw GarminFailure("This workout is too complex to preview.")
            for (step in items.sortedBy { it.number("stepOrder") ?: 0.0 }) {
                if (lines.size >= 200) throw GarminFailure("This workout has too many steps to preview.")
                val prefix = "  ".repeat(depth)
                if (step.text("type") == "RepeatGroupDTO" || step.objects("workoutSteps").isNotEmpty()) {
                    lines += prefix + "Repeat ${(step.number("numberOfIterations") ?: step.number("endConditionValue"))?.let(::n) ?: "(count unavailable)"} times"
                    steps(step.objects("workoutSteps"), depth + 1)
                } else {
                    val value = step.number("endConditionValue")
                    val duration = when (val kind = step.key("endCondition", "conditionTypeKey")) {
                        "time" -> value?.let { "${n(it / 60)} min" } ?: "Duration unavailable"
                        "distance" -> value?.let { "${n(it / 1609.344)} mi" } ?: "Distance unavailable"
                        "lap.button" -> "Until lap press"
                        else -> "End condition: ${kind.ifBlank { "unavailable" }}"
                    }
                    val type = step.key("targetType", "workoutTargetTypeKey")
                    val low = step.number("targetValueOne")
                    val high = step.number("targetValueTwo")
                    val range = if (low != null && high != null) "${n(low)}–${n(high)}" else null
                    val zone = step.number("zoneNumber")?.takeIf { it > 0 }
                    val target = when (type) {
                        "no.target" -> "No target"
                        "heart.rate.zone" -> if (zone != null) "HR zone ${n(zone)} (Garmin zones)" else range?.let { "$it bpm" } ?: "HR target unavailable"
                        else -> "${type.ifBlank { "Unknown target" }}${range?.let { ": $it (Garmin values; units not verified)" }.orEmpty()}"
                    }
                    lines += "$prefix${step.key("stepType", "stepTypeKey").ifBlank { "Step" }} · $duration · $target"
                }
            }
        }
        root.objects("workoutSegments").forEach { steps(it.objects("workoutSteps"), 0) }
        if (lines.isEmpty()) throw GarminFailure("Garmin returned this workout without readable steps.")
        val executable = runCatching { executable(root) }
        return GarminWorkoutPreview(root.text("workoutName").ifBlank { "Garmin workout" }, root.key("sportType", "sportTypeKey"), lines,
            executable.getOrDefault(emptyList()), executable.exceptionOrNull()?.message)
    }
    private fun executable(root: JsonObject): List<dev.digitalducktape.openrun.PlannedStep> {
        require(root.key("sportType", "sportTypeKey") in setOf("running", "treadmill_running")) { "Only running workouts can be started on this treadmill." }
        val result=mutableListOf<dev.digitalducktape.openrun.PlannedStep>()
        fun expand(items:List<JsonObject>,depth:Int) {
            require(depth<=6) { "Workout repeats are too deeply nested." }
            for(step in items.sortedBy { it.number("stepOrder") ?: 0.0 }) {
                val children=step.objects("workoutSteps")
                if(children.isNotEmpty()) {
                    val count=step.number("numberOfIterations") ?: step.number("endConditionValue")
                    require(count!=null && count in 1.0..100.0 && count%1.0==0.0) { "Unsupported repeat count." }
                    require(step.key("endCondition","conditionTypeKey") == "iterations") { "Only fixed-count repeats can run automatically." }
                    val skip=(step["skipLastRestStep"] as? JsonPrimitive)?.booleanOrNull==true
                    val ordered=children.sortedBy { it.number("stepOrder") ?: 0.0 }
                    repeat(count.toInt()) { iteration ->
                        val omit=skip && iteration==count.toInt()-1 && ordered.last().key("stepType","stepTypeKey") in setOf("rest","recovery")
                        expand(if(omit) ordered.dropLast(1) else ordered,depth+1)
                    }
                } else {
                    require(result.size<200) { "Too many workout steps." }
                    require(step.key("endCondition","conditionTypeKey")=="time") { "This workout includes distance, lap, or another unsupported end condition. Preview only." }
                    val seconds=step.number("endConditionValue")
                    require(seconds!=null && seconds in 1.0..14400.0 && seconds%1.0==0.0) { "Unsupported step duration." }
                    val target=step.key("targetType","workoutTargetTypeKey")
                    val low=step.number("targetValueOne"); val high=step.number("targetValueTwo")
                    val noTarget=target=="no.target"
                    val pace=target=="pace.zone" && (step.number("zoneNumber") ?: 0.0)==0.0 && low!=null && high!=null && low in 0.89408..4.4704 && high in 0.89408..4.4704
                    require(noTarget || pace || (target=="heart.rate.zone" && (step.number("zoneNumber") ?: 0.0)==0.0 && low!=null && high!=null && low in 30.0..240.0 && high in low..240.0 && low%1.0==0.0 && high%1.0==0.0)) { "This target needs absolute HR bounds or a supported running pace before it can run automatically. Preview only." }
                    result+=dev.digitalducktape.openrun.PlannedStep(step.key("stepType","stepTypeKey").ifBlank { "Run" },seconds.toInt(),if(noTarget || pace) null else low!!.toInt(),if(noTarget || pace) null else high!!.toInt(),
                        paceLowMph=if(pace) minOf(low!!,high!!)/.44704 else null,paceHighMph=if(pace) maxOf(low!!,high!!)/.44704 else null)
                }
            }
        }
        root.objects("workoutSegments").forEach { expand(it.objects("workoutSteps"),0) }
        require(result.isNotEmpty()) { "No executable steps." }
        return result
    }

}
