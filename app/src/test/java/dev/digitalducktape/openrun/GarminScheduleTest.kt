package dev.digitalducktape.openrun.core.garmin

import dev.digitalducktape.openrun.PlannedStep
import java.time.LocalDate
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class GarminScheduleTest {
    private val today=LocalDate.of(2026,9,27)
    private fun workout(date: LocalDate=today, id: Long=1)=GarminPlannedWorkout(id,id,date.toString(),"Base")
    private fun details(w: GarminPlannedWorkout)=GarminWorkoutPreview(w.name,"running",listOf("run"),listOf(PlannedStep("run",600,120,140)),source=w)
    @Test fun `cache survives offline failure and restart without leaking across profiles`() = runBlocking {
        var data=emptyList<GarminSchedule>(); var failed=false; var calls=0
        fun repo()=GarminScheduleRepository({"account-$it"},{data},{ c -> data=data.filterNot { it.profileId==c.profileId }+c },
            { calls++; if(failed) throw IOException(); listOf(workout()) },{_,w -> details(w)},{1_000_000})
        repo().refresh(1,today)
        repo().refresh(2,today)
        data=Json.decodeFromString(Json.encodeToString(data))
        failed=true
        val restart=repo()
        restart.refresh(1,today,force=true)
        assertEquals(2,data.size)
        assertEquals("account-1",data.first { it.profileId==1L }.accountKey)
        assertEquals(600,data.first().entries.single().preview.executableSteps.single().seconds)
        assertNotNull(restart.status.value[1]?.error)
        assertEquals(3,calls)
    }
    @Test fun `successful empty schedule clears cancelled workouts and recent returns are throttled`() = runBlocking {
        var data=emptyList<GarminSchedule>(); var all=listOf(workout()); var calls=0
        val repo=GarminScheduleRepository({"a"},{data},{data=listOf(it)},{ calls++; all },{_,w -> details(w)},{1_000_000})
        repo.refresh(1,today); repo.refresh(1,today)
        assertEquals(1,calls)
        all=emptyList(); repo.refresh(1,today,force=true)
        assertTrue(data.single().entries.isEmpty())
        assertEquals(2,calls)
    }
    @Test fun `account change during fetch never saves the previous account schedule`() = runBlocking {
        var owner="a"; var data=emptyList<GarminSchedule>()
        val repo=GarminScheduleRepository({owner},{data},{data=listOf(it)},{listOf(workout())},{_,w -> owner="b";details(w)})
        repo.refresh(1,today)
        assertTrue(data.isEmpty())
        assertNull(repo.status.value[1])
    }
    @Test fun `next workout beyond week is cached and preview failure retains previous snapshot`() = runBlocking {
        var data=emptyList<GarminSchedule>(); var failed=false
        val next=workout(today.plusDays(10))
        val repo=GarminScheduleRepository({"a"},{data},{data=listOf(it)},{listOf(next,workout(today.plusDays(15),2))},
            {_,w -> if(failed) throw IOException(); details(w) })
        repo.refresh(1,today)
        assertEquals(listOf(next),data.single().entries.map { it.source })
        val old=data
        failed=true; repo.refresh(1,today,force=true)
        assertEquals(old,data)
        assertNotNull(repo.status.value[1]?.error)
    }
}
