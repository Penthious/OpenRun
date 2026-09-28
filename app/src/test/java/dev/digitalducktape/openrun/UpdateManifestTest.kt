package dev.digitalducktape.openrun

import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateManifestTest {
    private val valid = UpdateManifest(37, "0.2.35", "OpenRun-v0.2.35.apk", "a".repeat(64))
    @Test fun acceptsExpectedRelease() { valid.validate("v0.2.35") }
    @Test fun rejectsMismatchedTagAndUnsafeFilename() {
        assertThrows(IllegalArgumentException::class.java) { valid.validate("v0.2.36") }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(apk = "../../update.apk").validate("v0.2.35") }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(sha256 = "1234").validate("v0.2.35") }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(versionCode = -1).validate("v0.2.35") }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(versionName = "0.2.35-beta").validate("v0.2.35-beta") }
    }
}
