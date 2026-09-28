package dev.digitalducktape.openrun.core.garmin

import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable data class ScheduleEntry(val source: GarminPlannedWorkout, val preview: GarminWorkoutPreview)
@Serializable data class GarminSchedule(val profileId: Long, val accountKey: String, val syncedAt: Long,
    val fromDate: String, val entries: List<ScheduleEntry>)
data class ScheduleRefresh(val loading: Boolean = false, val error: String? = null)

/** Account ownership is checked again after network calls, including when the user switches accounts. */
class GarminScheduleRepository(
    private val accountKey: (Long) -> String?,
    private val cached: () -> List<GarminSchedule>,
    private val save: (GarminSchedule) -> Unit,
    private val list: suspend (Long) -> List<GarminPlannedWorkout>,
    private val preview: suspend (Long, GarminPlannedWorkout) -> GarminWorkoutPreview,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    val status = MutableStateFlow<Map<Long, ScheduleRefresh>>(emptyMap())
    suspend fun refresh(profileId: Long, today: LocalDate, force: Boolean = false) = lock.withLock {
        val owner = accountKey(profileId) ?: return@withLock
        val old = cached().firstOrNull { it.profileId == profileId && it.accountKey == owner }
        if (!force && old != null && old.fromDate == today.toString() && now() - old.syncedAt in 0 until 300_000) return@withLock
        status.value = status.value + (profileId to ScheduleRefresh(loading = true))
        try {
            val all = list(profileId).filter { it.date >= today.toString() }.sortedBy { it.date }
            // Upcoming seven days plus the next scheduled day when it falls outside the strip.
            val next = all.firstOrNull()?.date
            val selected = all.filter { it.date <= today.plusDays(6).toString() || it.date == next }
            require(selected.size <= 40) { "Too many scheduled workouts" }
            val entries = selected.map { ScheduleEntry(it, preview(profileId, it).let { details -> details.copy(source = details.source ?: it) }) }
            if (accountKey(profileId) == owner) {
                save(GarminSchedule(profileId, owner, now(), today.toString(), entries))
                status.value = status.value + (profileId to ScheduleRefresh())
            } else status.value = status.value - profileId
        } catch (cancelled: CancellationException) {
            status.value = status.value - profileId
            throw cancelled
        } catch (e: Exception) {
            status.value = status.value + (profileId to ScheduleRefresh(error =
                (e as? GarminFailure)?.userMessage ?: "Could not refresh Garmin. Saved workouts are still available."))
        }
    }
    companion object {
        fun ownerKey(email: String): String = MessageDigest.getInstance("SHA-256")
            .digest(email.trim().lowercase(java.util.Locale.ROOT).toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
