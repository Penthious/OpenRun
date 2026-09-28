package dev.digitalducktape.openrun.core.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/** Atomic snapshots; a recording is always owned by the profile selected at its creation. */
class RunStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "workouts.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // Never silently overwrite unreadable history with an empty store.
    private val initial = if (file.baseFile.exists()) json.decodeFromString<SavedState>(file.openRead().bufferedReader().use { it.readText() }) else SavedState()
    private val mutable = MutableStateFlow(initial)
    val state = mutable.asStateFlow()
    @Synchronized fun update(change: (SavedState) -> SavedState) {
        val next = change(mutable.value)
        val bytes = json.encodeToString(next).toByteArray()
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        mutable.value = next
    }
    fun addProfile(name: String) {
        val trimmed = name.trim(); require(trimmed.isNotEmpty() && trimmed.length <= 40)
        update { s -> val id = (s.profiles.maxOfOrNull { it.id } ?: 0) + 1; s.copy(profiles = s.profiles + Profile(id, trimmed), selectedId = id) }
    }
    fun save(ride: Ride) = update { state ->
        val old = state.rides.firstOrNull { it.id == ride.id }
        if (old != null && old.status != "recording" && ride.status == "recording") state
        else state.copy(rides = state.rides.filterNot { it.id == ride.id } + ride)
    }
    fun history(profileId: Long) = state.value.rides.filter { it.profileId == profileId && it.status != "recording" }.sortedByDescending { it.id }
}
