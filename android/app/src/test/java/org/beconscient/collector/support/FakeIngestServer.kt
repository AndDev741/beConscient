package org.beconscient.collector.support

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.beconscient.collector.crypto.Hpke
import org.beconscient.collector.crypto.ResponseCipher
import org.beconscient.collector.util.Base64Url
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plays the ingest side of contract/envelope.md with the test private key: it
 * opens envelopes, logs batches by sequence number, returns the stored receipt
 * for an identical resend, answers 409 for a different list under a used number,
 * and encrypts every 200 with the exported response key. Tests bend it with [next].
 */
class FakeIngestServer : Dispatcher() {

    /** What the next response does instead of the normal answer. */
    sealed interface Twist {
        /** Process the request, then drop the connection without answering. */
        data object DropResponse : Twist
        /** Answer 200 with a response encrypted under the wrong key. */
        data object WrongKey : Twist
        /** Answer 200 with a tampered ciphertext. */
        data object TamperedTag : Twist
        /** Answer 200 with an HTML page, as a captive portal does. */
        data object CaptivePortal : Twist
        /** Answer with this status and an error body, without processing. */
        data class Status(val code: Int, val error: String = "unavailable") : Twist
        /** Add these results to the receipt (ids the batch did not carry). */
        data class ExtraResults(val results: List<Pair<String, String>>) : Twist
        /** Answer with a receipt for another sequence number. */
        data object WrongSeq : Twist
    }

    data class Seen(val path: String, val plaintext: JsonObject, val raw: String)

    val server = MockWebServer().apply { dispatcher = this@FakeIngestServer }
    val requests = CopyOnWriteArrayList<Seen>()
    val twists = ArrayDeque<Twist>()

    /** Server-side verdict per event; default accepts everything. */
    var verdict: (JsonObject) -> Pair<String, String?> = { "accepted" to null }

    /** Pairing answer; default echoes a device and a fingerprint. */
    var pairResponse: (JsonObject) -> JsonObject = {
        buildJsonObject {
            put("device_id", "0192f3a0-0000-7000-8000-0000000000aa")
            put("device_name", it["name"]!!.jsonPrimitive.content)
            put("fingerprint", "ABCD-EFGH")
        }
    }

    private val logged = HashMap<Long, Pair<List<String>, String>>()
    private val seenIds = HashSet<String>()
    private val random = SecureRandom()

    fun url(): String = server.url("/").toString().trimEnd('/')

    fun next(twist: Twist) = synchronized(twists) { twists.addLast(twist) }

    fun batches(): List<JsonObject> = requests.filter { it.path.endsWith("/batches") }.map { it.plaintext }

    /** Not named shutdown(): MockWebServer calls Dispatcher.shutdown() itself while stopping. */
    fun stop() = server.shutdown()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val twist = synchronized(twists) { twists.removeFirstOrNull() }
        if (twist is Twist.Status) return error(twist.code, twist.error)

        val path = request.path.orEmpty()
        val raw = request.body.readUtf8()
        val body = parse(raw)
        val info = when {
            path.endsWith("/ingest/v1/batches") -> Hpke.INFO_BATCH
            path.endsWith("/ingest/v1/pair") -> Hpke.INFO_PAIR
            else -> return error(404, "not_found")
        }
        if (body["kid"]?.jsonPrimitive?.content != TestKeys.kid) return error(401, "unauthorized")
        val opened = try {
            Hpke.open(
                TestKeys.privateKey,
                TestKeys.publicKey,
                TestKeys.kid,
                info,
                Base64Url.decode(body["enc"]!!.jsonPrimitive.content),
                Base64Url.decode(body["ct"]!!.jsonPrimitive.content),
            )
        } catch (e: Exception) {
            return error(400, "bad_envelope")
        }
        val plaintext = parse(opened.plaintext.toString(Charsets.UTF_8))
        requests += Seen(path, plaintext, raw)

        val answer: String = if (info == Hpke.INFO_PAIR) {
            pairResponse(plaintext).toString()
        } else {
            batchReceipt(plaintext, twist) ?: return error(409, "sequence_conflict")
        }

        if (twist == Twist.DropResponse) {
            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        val key = if (twist == Twist.WrongKey) ByteArray(32) { 7 } else opened.responseKey
        val nonce = ByteArray(12).also(random::nextBytes)
        val ct = ResponseCipher.seal(key, nonce, answer.toByteArray(Charsets.UTF_8))
        if (twist == Twist.TamperedTag) ct[ct.size - 1] = (ct[ct.size - 1].toInt() xor 1).toByte()
        if (twist == Twist.CaptivePortal) {
            return MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html")
                .setBody("<html><body>Please log in to the Wi-Fi</body></html>")
        }
        val out = buildJsonObject {
            put("v", 1)
            put("nonce", Base64Url.encode(nonce))
            put("ct", Base64Url.encode(ct))
        }
        return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(out.toString())
    }

    /** Returns the receipt JSON, or null for a sequence conflict. */
    private fun batchReceipt(plaintext: JsonObject, twist: Twist?): String? {
        val header = plaintext["header"]!!.jsonObject
        val seq = header["seq"]!!.jsonPrimitive.long
        val installation = header["installation_id"]!!.jsonPrimitive.content
        val events = plaintext["events"]!!.jsonArray.map { it.jsonObject }
        val ids = events.map { it["id"]!!.jsonPrimitive.content + "#" + (it["rev"]?.jsonPrimitive?.content ?: "") }
        synchronized(logged) {
            logged[seq]?.let { (loggedIds, receipt) ->
                return if (loggedIds == ids) receipt else null
            }
            val results = buildJsonArray {
                for (e in events) {
                    val id = e["id"]!!.jsonPrimitive.content
                    val (status, reason) = if (id in seenIds) "duplicate" to null else verdict(e)
                    if (status != "rejected") seenIds += id
                    addJsonObject {
                        put("id", id)
                        put("status", status)
                        if (reason != null) put("reason", reason)
                    }
                }
                if (twist is Twist.ExtraResults) {
                    for ((id, status) in twist.results) addJsonObject {
                        put("id", id)
                        put("status", status)
                    }
                }
            }
            val receipt = buildJsonObject {
                put("installation_id", installation)
                put("seq", if (twist == Twist.WrongSeq) seq + 100 else seq)
                put("results", results)
            }.toString()
            if (twist != Twist.WrongSeq) logged[seq] = ids to receipt
            return receipt
        }
    }

    private fun error(code: Int, error: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody("{\"error\":\"$error\"}")

    companion object {
        fun eventIds(batch: JsonObject): List<String> =
            batch["events"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }
}
