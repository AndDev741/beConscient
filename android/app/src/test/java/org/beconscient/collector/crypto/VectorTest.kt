package org.beconscient.collector.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.beconscient.collector.events.Events
import org.beconscient.collector.pairing.Credentials
import org.beconscient.collector.support.Contract
import org.beconscient.collector.support.TestKeys
import org.beconscient.collector.support.str
import org.beconscient.collector.upload.BatchPlaintext
import org.beconscient.collector.util.Base64Url
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.TimeZone

/**
 * Cross-implementation vectors (contract/README.md, "Test vectors"): every file
 * in contract/vectors is opened as the recipient with the test private key.
 * Run with -Pbeconscient.writeVectors=true to rewrite vectors/kotlin-android.json.
 */
class VectorTest {
    private val vectorDir = File(Contract.dir, "vectors")

    private fun vectors(file: File) = Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject }

    private fun verify(v: JsonObject) {
        val purpose = v.str("purpose")!!
        val info = v.str("info")!!
        assertEquals("beconscient/v1/$purpose", info)
        val req = v["request"]!!.jsonObject
        assertEquals(TestKeys.kid, req.str("kid"))
        val opened = Hpke.open(
            TestKeys.privateKey, TestKeys.publicKey, req.str("kid")!!, info,
            Base64Url.decode(req.str("enc")!!), Base64Url.decode(req.str("ct")!!),
        )
        assertEquals(v.str("plaintext"), opened.plaintext.toString(Charsets.UTF_8))
        assertArrayEquals(Base64Url.decode(v.str("response_key")!!), opened.responseKey)
        val resp = v["response"]!!.jsonObject
        val text = Envelope.openResponse(opened.responseKey, resp.toString())
        assertEquals(v.str("response_plaintext"), text)
    }

    @Test
    fun `go reference vectors open as recipient`() {
        val file = File(vectorDir, "go-reference.json")
        val vs = vectors(file)
        assertEquals(setOf("batch", "pair"), vs.map { it.str("purpose") }.toSet())
        vs.forEach(::verify)
    }

    @Test
    fun `every vector file in the contract opens`() {
        val files = vectorDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        assertTrue("kotlin-android.json is missing; run with -Pbeconscient.writeVectors=true",
            files.any { it.name == "kotlin-android.json" })
        for (f in files) for (v in vectors(f)) {
            try {
                verify(v)
            } catch (t: Throwable) {
                throw AssertionError("${f.name} ${v.str("producer")}/${v.str("purpose")}: ${t.message}", t)
            }
        }
    }

    @Test
    fun `write kotlin-android vectors`() {
        assumeTrue(System.getProperty("beconscient.writeVectors") == "true")
        val creds = Credentials(
            installationId = "0192f3a0-1c2b-7cde-8f00-112233445566",
            secret = ByteArray(32) { it.toByte() },
            deviceId = "", deviceName = "", fingerprint = "", serverFingerprint = "", pairedAt = 0,
        )
        // Real builders, fixed inputs: the vector shows exactly what this collector sends.
        val usage = Events.usage(1760090000000, "ACTIVITY_RESUMED", 1760089000000, "com.example.app", "com.example.app.MainActivity", 7)
            .payload.replace(Regex("\"id\":\"[^\"]+\""), "\"id\":\"0192f3a1-0000-7000-8000-000000000001\"")
        val batch = BatchPlaintext.build(creds, 1, 1760090000123, TimeZone.getTimeZone("Europe/Lisbon"), "0.0.0-test", listOf(usage))
        val receipt = """{"installation_id":"0192f3a0-1c2b-7cde-8f00-112233445566","seq":1,"results":[{"id":"0192f3a1-0000-7000-8000-000000000001","status":"accepted"}]}"""
        val pair = buildJsonObject {
            put("code", "7KQ2-M9XD")
            put("installation_id", creds.installationId)
            put("secret", Base64Url.encode(creds.secret))
            put("kind", "android")
            put("name", "Test phone")
            put("client_version", "0.0.0-test")
        }.toString()
        val pairResponse = """{"device_id":"0192f3a0-0000-7000-8000-0000000000aa","device_name":"Test phone","fingerprint":"${Fingerprint.of(creds.installationId, creds.secret)}"}"""

        val out = buildJsonArray {
            add(build("batch", batch, receipt))
            add(build("pair", pair, pairResponse))
        }
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val pretty = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
        File(vectorDir, "kotlin-android.json").writeText(pretty.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), out) + "\n")
    }

    private fun build(purpose: String, plaintext: String, response: String): JsonObject {
        val info = "beconscient/v1/$purpose"
        val sealed = Hpke.seal(TestKeys.publicKey, TestKeys.kid, info, plaintext.toByteArray())
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val rct = ResponseCipher.seal(sealed.responseKey, nonce, response.toByteArray())
        return buildJsonObject {
            put("producer", "kotlin-android")
            put("kid", TestKeys.kid)
            put("purpose", purpose)
            put("info", info)
            put("plaintext", plaintext)
            putJsonObject("request") {
                put("v", 1)
                put("kid", TestKeys.kid)
                put("enc", Base64Url.encode(sealed.enc))
                put("ct", Base64Url.encode(sealed.ct))
            }
            put("response_key", Base64Url.encode(sealed.responseKey))
            put("response_plaintext", response)
            putJsonObject("response") {
                put("v", 1)
                put("ct", Base64Url.encode(rct))
                put("nonce", Base64Url.encode(nonce))
            }
        }
    }
}
