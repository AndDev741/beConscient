package org.beconscient.collector.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.beconscient.collector.util.Base64Url

/** The JSON bodies that wrap HPKE output on the wire (contract/envelope.md). */
object Envelope {
    const val VERSION = 1

    /** A sealed request plus the key that will open its response. Never persisted. */
    class Request(val body: String, val responseKey: ByteArray)

    fun seal(publicKey: ByteArray, kid: String, info: String, plaintext: String): Request {
        val sealed = Hpke.seal(publicKey, kid, info, plaintext.toByteArray(Charsets.UTF_8))
        val body = buildJsonObject {
            put("v", VERSION)
            put("kid", kid)
            put("enc", Base64Url.encode(sealed.enc))
            put("ct", Base64Url.encode(sealed.ct))
        }
        return Request(body.toString(), sealed.responseKey)
    }

    /**
     * Opens an encrypted response body. Any malformed body, wrong version or
     * failed tag is a [BadResponse]: a captive portal, a proxy page or a forged
     * reply never reaches the caller as data.
     */
    fun openResponse(responseKey: ByteArray, body: String?): String {
        if (body.isNullOrBlank()) throw BadResponse("empty body")
        val obj = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw BadResponse("not a JSON object")
        val v = runCatching { obj["v"]?.jsonPrimitive?.int }.getOrNull()
        if (v != VERSION) throw BadResponse("unsupported version")
        val nonce = obj.b64("nonce") ?: throw BadResponse("missing nonce")
        val ct = obj.b64("ct") ?: throw BadResponse("missing ct")
        val pt = try {
            ResponseCipher.open(responseKey, nonce, ct)
        } catch (e: Exception) {
            throw BadResponse("response does not authenticate")
        }
        return pt.toString(Charsets.UTF_8)
    }

    private fun JsonObject.b64(name: String): ByteArray? = runCatching {
        Base64Url.decode(this[name]!!.jsonPrimitive.content)
    }.getOrNull()

    class BadResponse(message: String) : Exception(message)
}
