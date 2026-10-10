package org.beconscient.collector.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.beconscient.collector.util.Base64Url
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What pairing leaves on the device. [secret] is the batch credential. */
data class Credentials(
    val installationId: String,
    val secret: ByteArray,
    val deviceId: String,
    val deviceName: String,
    val fingerprint: String,
    val serverFingerprint: String,
    val pairedAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is Credentials && installationId == other.installationId && secret.contentEquals(other.secret)

    override fun hashCode(): Int = installationId.hashCode()
}

interface CredentialStore {
    fun load(): Credentials?
    fun save(credentials: Credentials)
    fun clear()
}

/** Encrypts and decrypts the secret at rest. The Android one keeps its key in the Keystore. */
interface SecretWrapper {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable Android Keystore key. Output is iv || ciphertext. */
class KeystoreSecretWrapper(private val alias: String = "beconscient-credential") : SecretWrapper {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, wrapped, 0, IV_LENGTH))
        return cipher.doFinal(wrapped, IV_LENGTH, wrapped.size - IV_LENGTH)
    }

    private companion object {
        const val IV_LENGTH = 12
    }
}

/** Private SharedPreferences, excluded from backup and transfer, with the secret wrapped. */
class PrefsCredentialStore(
    private val prefs: SharedPreferences,
    private val wrapper: SecretWrapper,
) : CredentialStore {

    @Volatile private var cached: Credentials? = null

    override fun load(): Credentials? {
        cached?.let { return it }
        val id = prefs.getString(K_INSTALLATION, null) ?: return null
        val wrapped = prefs.getString(K_SECRET, null) ?: return null
        val secret = try {
            wrapper.unwrap(Base64Url.decode(wrapped))
        } catch (e: Exception) {
            return null
        }
        return Credentials(
            installationId = id,
            secret = secret,
            deviceId = prefs.getString(K_DEVICE_ID, "").orEmpty(),
            deviceName = prefs.getString(K_DEVICE_NAME, "").orEmpty(),
            fingerprint = prefs.getString(K_FINGERPRINT, "").orEmpty(),
            serverFingerprint = prefs.getString(K_SERVER_FINGERPRINT, "").orEmpty(),
            pairedAt = prefs.getLong(K_PAIRED_AT, 0),
        ).also { cached = it }
    }

    override fun save(credentials: Credentials) {
        // commit, not apply: the next upload must find the credential on disk.
        prefs.edit(commit = true) {
            putString(K_INSTALLATION, credentials.installationId)
            putString(K_SECRET, Base64Url.encode(wrapper.wrap(credentials.secret)))
            putString(K_DEVICE_ID, credentials.deviceId)
            putString(K_DEVICE_NAME, credentials.deviceName)
            putString(K_FINGERPRINT, credentials.fingerprint)
            putString(K_SERVER_FINGERPRINT, credentials.serverFingerprint)
            putLong(K_PAIRED_AT, credentials.pairedAt)
        }
        cached = credentials
    }

    override fun clear() {
        prefs.edit(commit = true) { clear() }
        cached = null
    }

    companion object {
        const val PREFS_NAME = "credentials"
        private const val K_INSTALLATION = "installation_id"
        private const val K_SECRET = "secret_wrapped"
        private const val K_DEVICE_ID = "device_id"
        private const val K_DEVICE_NAME = "device_name"
        private const val K_FINGERPRINT = "fingerprint"
        private const val K_SERVER_FINGERPRINT = "server_fingerprint"
        private const val K_PAIRED_AT = "paired_at"

        fun android(context: Context): PrefsCredentialStore = PrefsCredentialStore(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            KeystoreSecretWrapper(),
        )
    }
}

/** For tests and previews. */
class InMemoryCredentialStore(private var value: Credentials? = null) : CredentialStore {
    override fun load() = value
    override fun save(credentials: Credentials) {
        value = credentials
    }
    override fun clear() {
        value = null
    }
}
