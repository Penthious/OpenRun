package dev.digitalducktape.openrun.core.garmin

import dev.digitalducktape.openrun.core.data.Ride
import dev.digitalducktape.openrun.core.data.RideSample
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GarminSyncManagerTest {
    private class MemoryStore : GarminPersistence {
        var state = GarminState()
        override fun read() = state
        override fun write(state: GarminState) { this.state = state }
    }
    private class Source(private val resistance: Int = 40) : GarminRideSource {
        val data = mutableListOf<Ride>()
        override suspend fun rides() = data.toList()
        override suspend fun samples(rideId: Long) = listOf(RideSample(1700000000000 + rideId * 100000, 1, 1.0, 0.9, resistance.toDouble(), 125, 0.0))
    }
    private class Api : GarminUploadApi {
        var failure: Exception? = null
        var uploads = 0
        var fitUploads = 0
        override suspend fun uploadFit(tokens: GarminTokens, fit: ByteArray): Boolean {
            fitUploads++; failure?.let { throw it }; return true
        }
        var refreshes = 0
        val importTokens = mutableListOf<String>()
        override suspend fun scheduledWorkouts(tokens: GarminTokens): List<GarminPlannedWorkout> {
            importTokens += tokens.access
            return listOf(GarminPlannedWorkout(10,20,"2026-09-27","Coach"))
        }
        val usedTokens = mutableListOf<String>()
        override suspend fun refresh(tokens: GarminTokens): GarminTokens {
            refreshes++
            return tokens.copy(access = "refreshed", refresh = "rotated", expiresAt = Long.MAX_VALUE)
        }
        override suspend fun upload(tokens: GarminTokens, tcx: String): Boolean {
            uploads++
            usedTokens += tokens.access
            failure?.let { throw it }
            return true
        }
    }
    private val tokens = GarminTokens("access", "refresh", "client", Long.MAX_VALUE)
    private fun ride(id: Long, profile: Long = 1) = Ride(id, profile, 1_700_000_000_000 + id * 100_000,
        1, 1.0, 0.0, "complete")

    @Test fun `planned FIT retries once without resending older TCX jobs`() = runBlocking {
        val store=MemoryStore(); val source=Source(); val api=Api()
        val sync=GarminSyncManager(store,source,api,{})
        sync.connect(1,"me@example.com",tokens); sync.setEnabled(1,true)
        source.data += ride(1)
        sync.sync()
        val plan=dev.digitalducktape.openrun.SavedWorkout("p",1,"Base",listOf(dev.digitalducktape.openrun.PlannedStep("run",60,120,140)))
        source.data += ride(2).copy(plannedWorkout=plan)
        api.failure=IOException()
        assertTrue(sync.sync())
        assertEquals("fit",store.state.uploads.last().format)
        api.failure=null
        sync.retry(1)
        GarminSyncManager(store,source,api,{}).sync()
        assertEquals(1,api.uploads)
        assertEquals(2,api.fitUploads)
        assertTrue(store.state.uploads.all { it.status=="uploaded" })
        assertTrue(store.state.uploads.last().message!!.contains("unverified"))
    }

    @Test fun `workout import uses selected profile refreshes tokens and never uploads`() = runBlocking {
        val store = MemoryStore(); val api = Api()
        val sync = GarminSyncManager(store, Source(), api, {})
        sync.connect(1, "one@example.com", tokens.copy(access="one"))
        sync.connect(2, "two@example.com", tokens.copy(access="two",expiresAt=0))
        assertEquals("Coach",sync.plannedWorkouts(2).single().name)
        assertEquals(listOf("refreshed"),api.importTokens)
        assertEquals("rotated",store.state.accounts.first { it.profileId==2L }.tokens.refresh)
        assertEquals("one",store.state.accounts.first { it.profileId==1L }.tokens.access)
        assertEquals(0,api.uploads)
        sync.disconnect(2)
        try { sync.plannedWorkouts(2); fail("Disconnected runner must not import") }
        catch (_: GarminFailure) { }
        assertEquals(1,api.importTokens.size)
    }

    @Test fun `only opted-in new rides for the connected profile are uploaded`() = runBlocking {
        val store = MemoryStore(); val source = Source(); val api = Api()
        source.data += ride(1)
        val sync = GarminSyncManager(store, source, api, {})
        sync.connect(1, "me@example.com", tokens)
        source.data += ride(2)
        sync.sync()
        assertEquals(0, api.uploads)
        sync.setEnabled(1, true)
        source.data += ride(3)
        source.data += ride(4, 2)
        sync.sync()
        assertEquals(listOf(3L), store.state.uploads.map { it.rideId })
        assertEquals("uploaded", store.state.uploads.single().status)
    }

    @Test fun `restart discovers saved rides and does not duplicate successful uploads`() = runBlocking {
        val store = MemoryStore(); val source = Source(); val api = Api()
        val sync = GarminSyncManager(store, source, api, {})
        sync.connect(1, "me@example.com", tokens); sync.setEnabled(1, true)
        source.data += ride(1) // Simulate death between saving the ride and scheduling work.
        GarminSyncManager(store, source, api, {}).sync()
        GarminSyncManager(store, source, api, {}).sync()
        assertEquals(1, api.uploads)
    }

    @Test fun `offline failures persist and retry with backoff after restart`() = runBlocking {
        val store = MemoryStore(); val source = Source(); val api = Api()
        var now = 1000L
        val sync = GarminSyncManager(store, source, api, {}, { now })
        sync.connect(1, "me@example.com", tokens); sync.setEnabled(1, true)
        source.data += ride(1)
        api.failure = IOException()
        assertTrue(sync.sync())
        api.failure = null
        assertTrue(GarminSyncManager(store, source, api, {}, { now }).sync())
        assertEquals(1, api.uploads)
        now += 60_000
        assertFalse(GarminSyncManager(store, source, api, {}, { now }).sync())
        assertEquals(2, api.uploads)
        assertEquals("uploaded", store.state.uploads.single().status)
    }

    @Test fun `different Garmin account never receives the old account queue`() = runBlocking {
        val store = MemoryStore(); val source = Source(); val api = Api()
        val sync = GarminSyncManager(store, source, api, {})
        sync.connect(1, "first@example.com", tokens); sync.setEnabled(1, true)
        source.data += ride(1); api.failure = IOException(); sync.sync()
        sync.connect(1, "second@example.com", tokens.copy(access = "second"))
        sync.setEnabled(1, true); api.failure = null; sync.sync()
        assertEquals(1, api.uploads)
        source.data += ride(2); sync.sync()
        assertEquals(listOf("access", "second"), api.usedTokens)
    }

    @Test fun `disabling retains existing queue but excludes rides completed while disabled`() = runBlocking {
        val store = MemoryStore(); val source = Source(); val api = Api()
        val sync = GarminSyncManager(store, source, api, {})
        sync.connect(1, "me@example.com", tokens); sync.setEnabled(1, true)
        source.data += ride(1); sync.setEnabled(1, false)
        source.data += ride(2); sync.sync(); assertEquals(0, api.uploads)
        sync.setEnabled(1, true); sync.sync()
        assertEquals(listOf(1L), store.state.uploads.map { it.rideId })
        assertEquals(1, api.uploads)
    }

    @Test fun `disconnect removes tokens and pending uploads and prevents further sync`() = runBlocking {
        val store = MemoryStore(); val source = Source(); val api = Api()
        val sync = GarminSyncManager(store, source, api, {})
        sync.connect(1, "me@example.com", tokens); sync.setEnabled(1, true)
        source.data += ride(1); api.failure = IOException(); sync.sync()
        sync.disconnect(1); api.failure = null; sync.sync()
        assertEquals(GarminState(), store.state)
        assertEquals(1, api.uploads)
    }

    @Test fun `expired tokens are refreshed and persisted before upload`() = runBlocking {
        val store = MemoryStore(); val source = Source(); val api = Api()
        val sync = GarminSyncManager(store, source, api, {})
        sync.connect(1, "me@example.com", tokens.copy(expiresAt = 0)); sync.setEnabled(1, true)
        source.data += ride(1); sync.sync()
        assertEquals(1, api.refreshes)
        assertEquals(listOf("refreshed"), api.usedTokens)
        assertEquals("rotated", store.state.accounts.single().tokens.refresh)
    }

    @Test fun `auth rejection requires reconnect and does not retry endlessly`() = runBlocking {
        val store = MemoryStore(); val source = Source(); val api = Api()
        val sync = GarminSyncManager(store, source, api, {})
        sync.connect(1, "me@example.com", tokens); sync.setEnabled(1, true)
        source.data += ride(1)
        api.failure = GarminFailure("Reconnect", authentication = true)
        assertFalse(sync.sync()); assertTrue(store.state.accounts.single().needsLogin)
        val attempted = api.uploads
        sync.sync(); assertEquals(attempted, api.uploads)
        api.failure = null
        sync.connect(1, "me@example.com", tokens); sync.sync()
        assertEquals("uploaded", store.state.uploads.single().status)
    }
}
