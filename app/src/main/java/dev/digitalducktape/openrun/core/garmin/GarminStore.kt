package dev.digitalducktape.openrun.core.garmin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class GarminTokens(val access: String, val refresh: String, val clientId: String, val expiresAt: Long)

@Serializable
data class GarminAccount(
    val profileId: Long,
    val email: String,
    val tokens: GarminTokens,
    val enabled: Boolean = false,
    val afterRideId: Long = 0,
    val needsLogin: Boolean = false,
    val message: String? = null,
)

@Serializable
data class GarminUpload(
    val profileId: Long,
    val email: String,
    val rideId: Long,
    val fingerprint: String,
    val status: String = "pending",
    val message: String? = null,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val format: String = "tcx",
)

@Serializable
data class GarminState(val accounts: List<GarminAccount> = emptyList(), val uploads: List<GarminUpload> = emptyList())

interface GarminPersistence {
    fun read(): GarminState
    fun write(state: GarminState)
}

/** Tokens and upload state stay on this device, outside Android and OpenRide backups. */
class GarminStore(context: Context) : GarminPersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, "garmin-sync.bin"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    override fun read(): GarminState {
        if (!file.baseFile.exists()) return GarminState()
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return json.decodeFromString(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }

    override fun write(state: GarminState) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val stream = file.startWrite()
        try {
            stream.write(cipher.iv)
            stream.write(cipher.doFinal(json.encodeToString(state).toByteArray(Charsets.UTF_8)))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private companion object { const val ALIAS = "openrun.garmin.v1" }
}
