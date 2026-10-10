package org.beconscient.collector.pairing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.beconscient.collector.crypto.Envelope
import org.beconscient.collector.crypto.Fingerprint
import org.beconscient.collector.crypto.Hpke
import org.beconscient.collector.events.Events
import org.beconscient.collector.events.TzMarkers
import org.beconscient.collector.queue.EventDao
import org.beconscient.collector.upload.HttpTransport
import org.beconscient.collector.upload.ServerConfig
import org.beconscient.collector.util.Base64Url
import org.beconscient.collector.util.Clock
import org.beconscient.collector.util.Crockford
import org.beconscient.collector.util.Uuid7
import java.io.IOException
import java.security.SecureRandom

/**
 * Pairs this installation with a device created on the page. The collector
 * makes its own installation id (UUIDv7) and 32-byte secret, sends them with
 * the one-time code inside a pairing envelope, and stores them only after a
 * response that opens with this request's key.
 */
class PairingClient(
    private val config: ServerConfig,
    private val transport: HttpTransport,
    private val store: CredentialStore,
    private val dao: EventDao,
    private val tzMarkers: TzMarkers,
    private val clock: Clock,
    private val clientVersion: String,
    private val random: SecureRandom = SecureRandom(),
) {
    sealed interface Result {
        data class Paired(val credentials: Credentials, val fingerprintsMatch: Boolean) : Result
        /** The code is not 8 Crockford base32 characters. */
        data object InvalidCode : Result
        /** 401: the code was already used, expired, mistyped, or codes are locked after failures. */
        data object CodeRejected : Result
        data class Failed(val reason: String) : Result
    }

    /** Accepts "7KQ2-M9XD", "7kq2m9xd" and look-alike letters; returns the XXXX-XXXX form. */
    fun normalizeCode(input: String): String? =
        Crockford.normalize(input)?.takeIf { it.length == 8 }?.let(Crockford::group4)

    suspend fun pair(codeInput: String, deviceName: String): Result {
        val code = normalizeCode(codeInput) ?: return Result.InvalidCode
        val now = clock.now()
        val installationId = Uuid7.generate(now)
        val secret = ByteArray(32).also(random::nextBytes)
        val name = deviceName.trim().ifEmpty { "Android phone" }.take(64)
        val plaintext = buildJsonObject {
            put("code", code)
            put("installation_id", installationId)
            put("secret", Base64Url.encode(secret))
            put("kind", "android")
            put("name", name)
            put("client_version", clientVersion)
        }.toString()
        val request = Envelope.seal(config.publicKey, config.kid, Hpke.INFO_PAIR, plaintext)
        val response = try {
            withContext(Dispatchers.IO) { transport.postJson(config.pairUrl, request.body) }
        } catch (e: IOException) {
            return Result.Failed("network")
        }
        when (response.code) {
            200 -> Unit
            401 -> return Result.CodeRejected
            429 -> return Result.Failed("rate_limited")
            else -> return Result.Failed("http_${response.code}")
        }
        val text = try {
            Envelope.openResponse(request.responseKey, response.body)
        } catch (e: Envelope.BadResponse) {
            return Result.Failed("bad_response")
        }
        val obj = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: return Result.Failed("bad_response")
        val deviceId = obj.string("device_id") ?: return Result.Failed("bad_response")
        val serverName = obj.string("device_name") ?: name
        val serverFingerprint = obj.string("fingerprint").orEmpty()
        val fingerprint = Fingerprint.of(installationId, secret)
        val creds = Credentials(
            installationId = installationId,
            secret = secret,
            deviceId = deviceId,
            deviceName = serverName,
            fingerprint = fingerprint,
            serverFingerprint = serverFingerprint,
            pairedAt = now,
        )
        store.save(creds)
        dao.resetForNewInstallation()
        tzMarkers.record(Events.TzReason.INITIAL)
        return Result.Paired(creds, fingerprintsMatch = serverFingerprint.equals(fingerprint, ignoreCase = true))
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
