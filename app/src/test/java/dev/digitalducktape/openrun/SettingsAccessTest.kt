package dev.digitalducktape.openrun

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsAccessTest {
    @Test fun preservesOtherAccessibilityServices() {
        assertEquals("reader/.Service:magnifier/.Service", withoutSettingsBlocker("reader/.Service:$BLOCKING_NAVIGATION_SERVICE:magnifier/.Service"))
    }
    @Test fun removesOnlyExactBlockerAndIsIdempotent() {
        assertEquals("", withoutSettingsBlocker(BLOCKING_NAVIGATION_SERVICE))
        assertEquals("", withoutSettingsBlocker(""))
        val other = "another.package/.AccessibilityServiceImpl"
        assertEquals(other, withoutSettingsBlocker(other))
    }
}
