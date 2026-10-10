package org.beconscient.collector.crypto

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.beconscient.collector.support.TestKeys
import org.beconscient.collector.support.parse
import org.beconscient.collector.support.str
import org.beconscient.collector.util.Base64Url
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

class EnvelopeTest {
    private val plaintext = """{"header":{"seq":1},"events":[]}"""

    private fun respond(key: ByteArray, text: String, nonce: ByteArray = ByteArray(12) { 3 }): String {
        val ct = ResponseCipher.seal(key, nonce, text.toByteArray())
        return buildJsonObject {
            put("v", 1)
            put("nonce", Base64Url.encode(nonce))
            put("ct", Base64Url.encode(ct))
        }.toString()
    }

    @Test
    fun `request opens with the test private key and both sides export the same response key`() {
        val req = Envelope.seal(TestKeys.publicKey, TestKeys.kid, Hpke.INFO_BATCH, plaintext)
        val body = parse(req.body)
        assertEquals("1", body.str("v"))
        assertEquals(TestKeys.kid, body.str("kid"))
        val enc = Base64Url.decode(body.str("enc")!!)
        assertEquals(32, enc.size)
        val opened = Hpke.open(
            TestKeys.privateKey, TestKeys.publicKey, TestKeys.kid, Hpke.INFO_BATCH,
            enc, Base64Url.decode(body.str("ct")!!),
        )
        assertEquals(plaintext, opened.plaintext.toString(Charsets.UTF_8))
        assertArrayEquals(req.responseKey, opened.responseKey)
    }

    @Test
    fun `base64url is unpadded and url safe`() {
        repeat(20) {
            val req = Envelope.seal(TestKeys.publicKey, TestKeys.kid, Hpke.INFO_PAIR, "x".repeat(it))
            val body = parse(req.body)
            for (f in listOf("enc", "ct")) {
                val v = body.str(f)!!
                assertFalse(v.contains('=') || v.contains('+') || v.contains('/'))
            }
        }
    }

    @Test
    fun `a pairing envelope does not open as a batch`() {
        val req = Envelope.seal(TestKeys.publicKey, TestKeys.kid, Hpke.INFO_PAIR, plaintext)
        val body = parse(req.body)
        assertThrows(Exception::class.java) {
            Hpke.open(
                TestKeys.privateKey, TestKeys.publicKey, TestKeys.kid, Hpke.INFO_BATCH,
                Base64Url.decode(body.str("enc")!!), Base64Url.decode(body.str("ct")!!),
            )
        }
    }

    @Test
    fun `the kid is bound as aad`() {
        val req = Envelope.seal(TestKeys.publicKey, TestKeys.kid, Hpke.INFO_BATCH, plaintext)
        val body = parse(req.body)
        assertThrows(Exception::class.java) {
            Hpke.open(
                TestKeys.privateKey, TestKeys.publicKey, "prod-2026-10", Hpke.INFO_BATCH,
                Base64Url.decode(body.str("enc")!!), Base64Url.decode(body.str("ct")!!),
            )
        }
    }

    @Test
    fun `response opens with the exported key`() {
        val req = Envelope.seal(TestKeys.publicKey, TestKeys.kid, Hpke.INFO_BATCH, plaintext)
        assertEquals("""{"ok":1}""", Envelope.openResponse(req.responseKey, respond(req.responseKey, """{"ok":1}""")))
    }

    @Test
    fun `forged, tampered or foreign responses are refused`() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val good = parse(respond(key, "{}"))
        val ct = Base64Url.decode(good.str("ct")!!)
        ct[0] = (ct[0].toInt() xor 1).toByte()
        val tampered = buildJsonObject {
            put("v", 1)
            put("nonce", good.str("nonce")!!)
            put("ct", Base64Url.encode(ct))
        }.toString()
        val bad = listOf(
            respond(ByteArray(32) { 9 }, "{}"),
            tampered,
            "<html>captive portal</html>",
            "",
            """{"v":2,"nonce":"${good.str("nonce")}","ct":"${good.str("ct")}"}""",
            """{"v":1,"nonce":"AAAA","ct":"${good.str("ct")}"}""",
            """{"error":"unavailable"}""",
        )
        for (b in bad) assertThrows(b, Envelope.BadResponse::class.java) { Envelope.openResponse(key, b) }
    }
}
