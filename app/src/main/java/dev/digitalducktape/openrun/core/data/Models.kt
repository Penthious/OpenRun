package dev.digitalducktape.openrun.core.data

import kotlinx.serialization.Serializable

@Serializable data class Profile(val id: Long, val name: String, val strapAddress: String? = null, val strapName: String? = null, val scheduleTimeZone: String = "America/Denver", val warmupEnabled: Boolean = true, val colorScheme: String = "charcoal")
@Serializable data class RideSample(val timestamp: Long, val elapsedSec: Int, val distanceMeters: Double, val speedMps: Double?, val incline: Double?, val heartRate: Int?, val ascentMeters: Double, val workoutStepIndex: Int? = null, val warmup: Boolean = false, val descentMeters: Double? = null)
@Serializable data class Ride(val id: Long, val profileId: Long, val startedAt: Long, val durationSec: Int = 0, val distanceMeters: Double = 0.0, val ascentMeters: Double = 0.0, val status: String = "recording", val samples: List<RideSample> = emptyList(), val plannedWorkout: dev.digitalducktape.openrun.SavedWorkout? = null, val guideCompleted: Boolean = false, val utcOffsetSeconds: Int = 0, val hikeName: String? = null, val descentMeters: Double? = null)
@Serializable data class SavedState(val profiles: List<Profile> = emptyList(), val selectedId: Long? = null, val rides: List<Ride> = emptyList(), val workouts: List<dev.digitalducktape.openrun.SavedWorkout> = emptyList(), val schedules: List<dev.digitalducktape.openrun.core.garmin.GarminSchedule> = emptyList(), val paceHistory: List<dev.digitalducktape.openrun.PaceHistory> = emptyList())
