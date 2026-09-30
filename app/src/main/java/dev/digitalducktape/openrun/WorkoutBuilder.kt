package dev.digitalducktape.openrun

import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

@Serializable data class IntervalDraft(
    val name:String="Run", val distance:Boolean=false, val amount:String="5",
    val speed:String="4", val incline:String="1", val useHr:Boolean=false,
    val low:String="120", val high:String="140",
)
@Serializable data class WorkoutDraft(
    val id:String=UUID.randomUUID().toString(), val owner:Long, val name:String="My workout",
    val maxSpeed:String="6", val intervals:List<IntervalDraft> = listOf(IntervalDraft()),
) {
    fun workout():SavedWorkout {
        require(name.trim().isNotEmpty() && name.trim().length<=80) { "Enter a workout name (up to 80 characters)." }
        val max=maxSpeed.toDoubleOrNull()
        require(max!=null && max.isFinite() && max in 2.0..10.0) { "Maximum speed must be 2–10 mph." }
        require(intervals.size in 1..40) { "Use 1–40 intervals." }
        val steps=intervals.mapIndexed { index,d ->
            val label="Interval ${index+1}"
            require(d.name.trim().isNotEmpty() && d.name.trim().length<=80) { "$label needs a name." }
            val amount=d.amount.toDoubleOrNull()
            require(amount!=null && amount.isFinite() && (if(d.distance) amount in .01..50.0 else amount in (1.0/60)..240.0)) { "$label: enter 0.01–50 miles or 0.017–240 minutes." }
            val speed=d.speed.toDoubleOrNull();val incline=d.incline.toDoubleOrNull()
            require(speed!=null && speed.isFinite() && speed in 2.0..max && kotlin.math.abs(speed*10-(speed*10).roundToInt())<.00001) { "$label: speed must be 2–$max mph, in 0.1 mph steps." }
            require(incline!=null && incline.isFinite() && incline in 0.0..3.0 && kotlin.math.abs(incline*2-(incline*2).roundToInt())<.00001) { "$label: incline must be 0–3%, in 0.5% steps." }
            val low=if(d.useHr) d.low.toIntOrNull() else null;val high=if(d.useHr) d.high.toIntOrNull() else null
            require(!d.useHr || (low!=null && high!=null && low in 30..240 && high in low..240)) { "$label: enter a valid HR range (30–240 bpm)." }
            PlannedStep(d.name.trim(),if(d.distance) 0 else (amount*60).roundToInt(),low,high,startMph=speed,
                distanceMeters=if(d.distance) amount*1609.344 else null,incline=incline)
        }
        return SavedWorkout(id,owner,name.trim(),steps,maxMph=max,custom=true).also { PlannedWorkout(it) }
    }
    companion object {
        private fun number(n:Double)=String.format(Locale.US,"%.6f",n).trimEnd('0').trimEnd('.')
        fun from(plan:SavedWorkout,copy:Boolean=false)=WorkoutDraft(
            if(copy) UUID.randomUUID().toString() else plan.id,plan.profileId,
            if(copy) "${plan.name.take(73)} (copy)" else plan.name,number(plan.maxMph),
            plan.steps.map { IntervalDraft(it.name,it.distanceMeters!=null,number(it.distanceMeters?.div(1609.344) ?: (it.seconds/60.0)),
                number(it.startMph ?: 2.0),number(it.incline ?: 1.0),it.hrLow!=null,(it.hrLow ?: 120).toString(),(it.hrHigh ?: 140).toString()) },
        )
    }
}
fun SavedWorkout.durationLabel():String {
    val minutes=steps.filter { it.distanceMeters==null }.sumOf { it.seconds }/60.0
    val miles=steps.sumOf { it.distanceMeters ?: 0.0 }/1609.344
    return listOfNotNull(if(minutes>0) "%.1f min".format(minutes) else null,if(miles>0) "%.2f mi".format(miles) else null).joinToString(" + ")
}
