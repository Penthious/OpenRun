package dev.digitalducktape.openrun

import dev.digitalducktape.openrun.core.data.Profile
import dev.digitalducktape.openrun.core.data.SavedState
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class ProfileThemeTest {
    @Test fun oldProfilesRetainDefaultTheme() {
        val profile=Json.decodeFromString<Profile>("""{"id":1,"name":"Runner"}""")
        assertEquals("charcoal",profile.colorScheme)
    }
    @Test fun profileChoicesSurviveSaveAndRestoreIndependently() {
        val state=SavedState(profiles=listOf(Profile(1,"A",colorScheme="ocean"),Profile(2,"B",colorScheme="plum")),selectedId=1)
        val restored=Json.decodeFromString<SavedState>(Json.encodeToString(state))
        assertEquals(listOf("ocean","plum"),restored.profiles.map { it.colorScheme })
        assertEquals(state,restored)
    }
    @Test fun unsupportedThemeFallsBackAndIdsAreUnique() {
        assertEquals("charcoal",runPalette("future-theme").id)
        assertEquals(runPalettes.size,runPalettes.map { it.id }.distinct().size)
    }
    @Test fun primaryTextAndSecondaryTextRemainLegibleAcrossPalettes() {
        fun luminance(color:Long):Double {
            val channels=listOf(16,8,0).map { shift ->
                val value=((color shr shift) and 255)/255.0
                if(value<=.04045) value/12.92 else ((value+.055)/1.055).pow(2.4)
            }
            return .2126*channels[0]+.7152*channels[1]+.0722*channels[2]
        }
        for(p in runPalettes) {
            assertTrue(p.name,(luminance(p.accent)+.05)/(luminance(p.background)+.05)>=4.5)
            assertTrue(p.name,(luminance(0xFFB9C0C5)+.05)/(luminance(p.surface)+.05)>=4.5)
        }
    }
}
