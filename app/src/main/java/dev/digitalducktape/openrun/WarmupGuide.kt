package dev.digitalducktape.openrun

import kotlin.math.abs
import kotlin.math.min

/** Five one-minute stages. Skipping ends this prelude, not any prescribed Garmin step. */
class WarmupGuide(private val pace: Double, private val ceiling: Int?) {
    var elapsed = 0L; private set
    var finished = false; private set
    var halted = false; private set
    var status = "Warm-up · 5:00"; private set
    private var previousAt: Long? = null
    private var previous: Telemetry? = null
    private var nextAt = 0L
    private var cooldown = 5_000L
    private var manualStage: Int? = null
    val stage get() = (elapsed / 60_000).toInt().coerceAtMost(4)
    val goal get() = listOf(2.0, min(3.5,2.0+(pace-2.0)*.4), 2.0+(pace-2.0)*.65, 2.0+(pace-2.0)*.8, 2.0+(pace-2.0)*.95)[stage].coerceIn(2.0,pace.coerceAtLeast(2.0))
    fun pause() { previousAt=null; previous=null }
    fun resume(now:Long) { halted=false; previousAt=now; previous=null; nextAt=now+5_000 }
    fun manual() { manualStage=stage }
    fun halt() { halted=true; pause(); status="Warm-up paused · check connection and resume" }
    fun skip() { finished=true; pause() }
    fun confirmed(now:Long,t:Telemetry) { previous=t; nextAt=now+cooldown }
    fun tick(now:Long,t:Telemetry,hr:Int?,fresh:Boolean,paused:Boolean,busy:Boolean):Target? {
        if(finished || halted) return null
        if(paused) { pause(); return null }
        if(!fresh || t.mph==null || t.mph<0.2 || t.incline==null || t.incline !in 0.0..3.15) { halt(); return null }
        elapsed+=(previousAt?.let { (now-it).coerceAtLeast(0) } ?: 0L); previousAt=now
        if(elapsed>=300_000) { finished=true; status="Warm-up complete"; return null }
        if(!busy && previous!=null && (abs(t.mph-previous!!.mph!!)>0.08 || abs(t.incline-previous!!.incline!!)>0.15)) manual()
        previous=t
        val seconds=((300_000-elapsed+999)/1000).toInt()
        status="Warm-up ${stage+1}/5 · ${seconds/60}:${"%02d".format(seconds%60)} left · goal ${"%.1f".format(goal)} mph"
        if(hr==null) { status+=" · HR disconnected, holding"; nextAt=now+10_000; return null }
        if(manualStage==stage) { status+=" · manual hold"; return null }
        if(busy || now<nextAt) return null
        cooldown=5_000; nextAt=now+cooldown
        if(ceiling!=null && hr>ceiling) {
            cooldown=30_000; nextAt=now+cooldown
            return if(t.incline>1.05) Target.Incline((t.incline-.5).coerceAtLeast(1.0))
                else if(t.mph>2.05) Target.Speed((t.mph-.1).coerceAtLeast(2.0)) else null
        }
        // Warm-up never adds incline to chase HR. Set a gentle 1% baseline only.
        if(t.incline>1.05) return Target.Incline((t.incline-.5).coerceAtLeast(1.0))
        val delta=(goal-t.mph).coerceIn(-.2,.2)
        return if(abs(delta)>.08) Target.Speed(kotlin.math.round((t.mph+delta)*10)/10) else null
    }
}
