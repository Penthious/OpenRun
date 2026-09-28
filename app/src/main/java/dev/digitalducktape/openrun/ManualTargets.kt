package dev.digitalducktape.openrun

/** Retain the newest request per axis, with bounded memory and no intermediate replay. */
internal fun coalesceManualTarget(pending: List<Target>, target: Target): List<Target> {
    val index=pending.indexOfFirst { (it is Target.Speed)==(target is Target.Speed) }
    return if(index<0) pending+target else pending.mapIndexed { i, old -> if(i==index) target else old }
}
