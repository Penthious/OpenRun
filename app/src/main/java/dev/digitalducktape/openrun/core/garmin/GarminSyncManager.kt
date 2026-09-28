package dev.digitalducktape.openrun.core.garmin

import dev.digitalducktape.openrun.core.data.Ride
import dev.digitalducktape.openrun.core.data.RideSample
import dev.digitalducktape.openrun.core.export.TcxExporter
import dev.digitalducktape.openrun.core.export.FitExporter
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface GarminRideSource {
    suspend fun rides(): List<Ride>
    suspend fun samples(rideId: Long): List<RideSample>
}

/** Durable outbox. A single lock serializes workers, reconnects, toggles and disconnects. */
class GarminSyncManager(
    private val store: GarminPersistence,
    private val rides: GarminRideSource,
    private val api: GarminUploadApi,
    private val schedule: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val initial = runCatching { store.read() }
    val storageError = if (initial.isFailure) "Saved Garmin connection could not be opened. Please reconnect." else null
    private val _state = MutableStateFlow(initial.getOrDefault(GarminState()))
    val state = _state.asStateFlow()

    suspend fun history(profileId: Long) = readWorkouts(profileId) { api.history(it) }

    suspend fun plannedWorkouts(profileId: Long, today: java.time.LocalDate? = null): List<GarminPlannedWorkout> = readWorkouts(profileId) { if(today==null) api.scheduledWorkouts(it) else api.scheduledWorkouts(it,today) }
    suspend fun previewWorkout(profileId: Long, workout: GarminPlannedWorkout): GarminWorkoutPreview =
        readWorkouts(profileId) { api.workoutPreview(it, workout) }

    // Share the upload lock so refresh token rotation cannot race a background upload.
    private suspend fun <T> readWorkouts(profileId: Long, read: suspend (GarminTokens) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            var current = _state.value.accounts.firstOrNull { it.profileId == profileId }
                ?: throw GarminFailure("Connect Garmin for this runner in Connections first.")
            if (current.needsLogin) throw GarminFailure("Reconnect Garmin in Connections first.")
            suspend fun refresh() {
                current = current.copy(tokens = api.refresh(current.tokens))
                account(current)
            }
            try {
                if (current.tokens.expiresAt <= now() + 60_000) refresh()
                try { read(current.tokens) } catch (failure: GarminFailure) {
                    if (!failure.authentication) throw failure
                    refresh()
                    read(current.tokens)
                }
            } catch (failure: GarminFailure) {
                if (failure.authentication) account(current.copy(needsLogin = true, message = failure.userMessage))
                throw failure
            }
        }
    }

    private fun persist(value: GarminState) {
        store.write(value)
        _state.value = value
    }

    private fun account(value: GarminAccount) = persist(_state.value.copy(
        accounts = _state.value.accounts.filterNot { it.profileId == value.profileId } + value,
    ))

    private fun upload(value: GarminUpload) = persist(_state.value.copy(
        uploads = _state.value.uploads.filterNot {
            it.profileId == value.profileId && it.email == value.email && it.fingerprint == value.fingerprint
        } + value,
    ))

    suspend fun connect(profileId: Long, email: String, tokens: GarminTokens) = withContext(Dispatchers.IO) {
        lock.withLock {
            val address = email.trim().lowercase(java.util.Locale.ROOT)
            val old = _state.value.accounts.firstOrNull { it.profileId == profileId && it.email == address }
            val cutoff = rides.rides().maxOfOrNull { it.id } ?: 0
            // A rider may replace the connected Garmin account. Pending uploads belong to
            // the old account and must never be sent to the new one.
            if (_state.value.accounts.any { it.profileId == profileId && it.email != address }) {
                persist(_state.value.copy(uploads = _state.value.uploads.filterNot { it.profileId == profileId }))
            }
            // Reauthentication of the same account preserves its queue and auto-upload choice.
            // A different account starts disabled and cannot receive the previous account's queue.
            account(GarminAccount(profileId, address, tokens, old?.enabled ?: false, old?.afterRideId ?: cutoff))
            if (old != null) persist(_state.value.copy(uploads = _state.value.uploads.map {
                if (it.profileId == profileId && it.email == address && it.status != "uploaded") {
                    it.copy(status = "pending", nextAttemptAt = 0, message = null)
                } else it
            }))
        }
        schedule()
    }

    suspend fun setEnabled(profileId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = _state.value.accounts.firstOrNull { it.profileId == profileId } ?: return@withLock
            if (current.enabled == enabled) return@withLock
            // Discover outstanding rides before disabling so an offline completed ride is retained.
            if (current.enabled) discover(current)
            val cutoff = if (enabled) rides.rides().maxOfOrNull { it.id } ?: 0 else current.afterRideId
            account(current.copy(enabled = enabled, afterRideId = cutoff))
        }
        schedule()
    }

    suspend fun disconnect(profileId: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            persist(_state.value.copy(
                accounts = _state.value.accounts.filterNot { it.profileId == profileId },
                uploads = _state.value.uploads.filterNot { it.profileId == profileId },
            ))
        }
    }

    /** Restore/delete must unlink account ownership before local profile IDs can be reused. */
    suspend fun disconnectAll() = withContext(Dispatchers.IO) { lock.withLock { persist(GarminState()) } }

    suspend fun retry(profileId: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            persist(_state.value.copy(uploads = _state.value.uploads.map {
                if (it.profileId == profileId && it.status != "uploaded") it.copy(status = "pending", nextAttemptAt = 0, message = null) else it
            }))
        }
        schedule()
    }

    private suspend fun discover(account: GarminAccount) {
        for (ride in rides.rides().filter { it.profileId == account.profileId && it.id > account.afterRideId && it.durationSec > 0 }) {
            if (_state.value.uploads.any { it.profileId == account.profileId && it.email == account.email && it.rideId == ride.id }) continue
            val format = if (ride.plannedWorkout != null) "fit" else "tcx"
            val fingerprint = fingerprint(payload(ride, rides.samples(ride.id), format))
            if (_state.value.uploads.none { it.profileId == account.profileId && it.email == account.email && it.fingerprint == fingerprint }) {
                upload(GarminUpload(account.profileId, account.email, ride.id, fingerprint, format = format))
            }
        }
    }

    /** Returns true when transient failures remain; WorkManager applies network-aware backoff. */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            var retry = false
            for (original in _state.value.accounts.filter { it.enabled && !it.needsLogin }) {
                discover(original)
                var current = original
                val pending = _state.value.uploads.filter {
                    it.profileId == current.profileId && it.email == current.email && it.status == "pending"
                }
                for (job in pending) {
                    if (job.nextAttemptAt > now()) { retry = true; continue }
                    val ride = rides.rides().firstOrNull { it.id == job.rideId && it.profileId == job.profileId }
                    if (ride == null) {
                        upload(job.copy(status = "failed", message = "The local ride was deleted."))
                        continue
                    }
                    val bytes = try { payload(ride, rides.samples(ride.id), job.format) } catch (_: Exception) {
                        upload(job.copy(status = "failed", message = "Could not export the saved workout. Your local history is unchanged."))
                        continue
                    }
                    if (fingerprint(bytes) != job.fingerprint) {
                        upload(job.copy(status = "failed", message = "The local ride changed. Reconnect Garmin before syncing restored rides."))
                        continue
                    }
                    try {
                        if (current.tokens.expiresAt <= now() + 60_000) {
                            current = current.copy(tokens = api.refresh(current.tokens))
                            account(current) // Persist rotated refresh tokens before issuing another request.
                        }
                        try {
                            check(if (job.format == "fit") api.uploadFit(current.tokens, bytes) else api.upload(current.tokens, bytes.toString(Charsets.UTF_8)))
                        } catch (failure: GarminFailure) {
                            if (!failure.authentication) throw failure
                            current = current.copy(tokens = api.refresh(current.tokens))
                            account(current)
                            check(if (job.format == "fit") api.uploadFit(current.tokens, bytes) else api.upload(current.tokens, bytes.toString(Charsets.UTF_8)))
                        }
                        upload(job.copy(status = "uploaded", message = if (job.format == "fit") "Uploaded to Garmin · Coach credit unverified" else "Uploaded to Garmin", attempts = job.attempts + 1))
                        current = current.copy(message = "Last upload completed", needsLogin = false)
                        account(current)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val failure = error as? GarminFailure
                        val transient = failure?.retryable ?: (error is IOException)
                        val message = failure?.userMessage ?: "Upload could not finish. Check your connection and retry."
                        upload(job.copy(status = if (transient) "pending" else "failed", message = message,
                            attempts = job.attempts + 1, nextAttemptAt = now() + retryDelay(job.attempts)))
                        current = current.copy(message = message, needsLogin = failure?.authentication == true)
                        account(current)
                        retry = retry || transient
                        // Do not hammer Garmin with the rest of the queue after an outage/rate limit.
                        if (transient || current.needsLogin) break
                    }
                }
            }
            retry
        }
    }

    companion object {
        internal fun matchesFingerprint(tcx: String, expected: String): Boolean = fingerprint(tcx) == expected

        private fun payload(ride: Ride, samples: List<RideSample>, format: String): ByteArray = when(format) {
            "tcx" -> TcxExporter.export(ride, samples).toByteArray(Charsets.UTF_8)
            "fit" -> FitExporter.export(ride, samples)
            else -> error("Unknown upload format")
        }
        internal fun fingerprint(tcx: String): String = fingerprint(tcx.toByteArray(Charsets.UTF_8))
        internal fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        internal fun retryDelay(attempt: Int): Long = (60_000L * (1L shl attempt.coerceIn(0, 6))).coerceAtMost(3_600_000L)
    }
}
