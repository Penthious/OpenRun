package dev.digitalducktape.openrun

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Pure decision engine. Caller supplies monotonic time and fresh observations. */
class ZoneTwo {
    var enabled=false; private set
    var status="Automatic adjustments off"; private set
    private var nextAt=0L
    private var overrideUntil=0L
    private var goodHrSince: Long?=null
    private var previous: Telemetry?=null
    private var expected: Target?=null
    private var origin: Telemetry?=null
    private var settleUntil=0L
    fun enable(now: Long, warmedUp: Boolean = false) { enabled=true; nextAt=now+if(warmedUp) 15_000 else 90_000; overrideUntil=0; goodHrSince=null; previous=null; expected=null; status=if(warmedUp) "Checking HR signal · 15 seconds" else "Warming up · 90 seconds" }
    fun disable(reason: String="Automatic adjustments off") { enabled=false; expected=null; goodHrSince=null; status=reason }
    fun manual(now: Long) { overrideUntil=now+60_000; nextAt=max(nextAt,overrideUntil); status="Manual override · 60 seconds" }
    fun commanded(target: Target,t: Telemetry,now: Long) { expected=target; origin=t; settleUntil=now+60_000; nextAt=now+45_000 }
    fun tick(now: Long,t: Telemetry,hr: Int?,fresh: Boolean,paused: Boolean): Target? {
        if(!enabled) return null
        if(!fresh || t.mph==null || t.incline==null) { disable("Treadmill connection lost · tap Enable Zone 2 to resume"); return null }
        if(paused) { goodHrSince=null; status="Paused"; return null }
        if(t.mph<0.2) { disable("Belt stopped · automatic adjustments off"); return null }
        if(t.mph>4.08 || t.incline>20.15 || t.incline< -0.15) { disable("Outside Zone 2 limits · automatic adjustments off"); return null }
        val target=expected
        val old=previous
        if(target!=null) {
            if(origin!=null && target.overridden(origin!!,t)) { expected=null; manual(now) }
            else if(target.matches(t)) expected=null
            else {
                val current=if(target is Target.Speed) t.mph else t.incline
                val start=if(target is Target.Speed) origin?.mph else origin?.incline
                val otherChanged=old!=null && if(target is Target.Speed) abs(t.incline-old.incline!!)>0.08 else abs(t.mph-old.mph!!)>0.05
                val outside=start==null || current<min(start,target.value)-0.15 || current>max(start,target.value)+0.15
                if(otherChanged || outside) { expected=null; manual(now) }
                else if(now>=settleUntil) { disable("Target not reached · automatic adjustments off"); return null }
            }
        } else if(old!=null && (abs(t.mph-old.mph!!)>0.05 || abs(t.incline-old.incline!!)>0.08)) manual(now)
        previous=t
        if(hr==null) { goodHrSince=null; status="HR disconnected · holding current settings"; return null }
        if(goodHrSince==null) goodHrSince=now
        if(now<overrideUntil) { status="Manual override · ${(overrideUntil-now+999)/1000}s"; return null }
        if(now-goodHrSince!!<15_000) { status="Checking HR signal · holding settings"; return null }
        if(expected!=null) { status="Waiting for treadmill to reach target"; return null }
        if(now<nextAt) { status="Next check in ${(nextAt-now+999)/1000}s · target 120–140"; return null }
        val decision=when {
            hr<120 && t.incline<20 -> Target.Incline(min(20.0,t.incline+0.5))
            hr<120 && t.mph<4 -> Target.Speed(min(4.0,t.mph+0.1))
            hr>140 && t.incline>0 -> Target.Incline(max(0.0,t.incline-0.5))
            hr>140 && t.mph>2 -> Target.Speed(max(2.0,t.mph-0.1))
            else -> null
        }
        status=when { hr in 120..140 -> "In Zone 2 · holding steady"; decision==null -> "At workout limit · adjust manually if needed"; decision is Target.Incline -> "Adjusting incline to ${"%.1f".format(java.util.Locale.US,decision.value)}%"; else -> "Adjusting speed to ${"%.1f".format(java.util.Locale.US,decision.value)} mph" }
        if(decision!=null) commanded(decision,t,now)
        return decision
    }
}
