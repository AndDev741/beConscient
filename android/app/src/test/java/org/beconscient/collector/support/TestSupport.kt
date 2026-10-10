package org.beconscient.collector.support

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.beconscient.collector.pairing.Credentials
import org.beconscient.collector.queue.EventDb
import org.beconscient.collector.setup.SensorProbe
import org.beconscient.collector.upload.ServerConfig
import org.beconscient.collector.util.Base64Url
import org.beconscient.collector.util.Clock
import java.io.File

class FakeClock(var now: Long = T0) : Clock {
    override fun now(): Long = now
    fun advance(ms: Long) {
        now += ms
    }

    companion object {
        /** 2025-10-10T10:00:00Z */
        const val T0 = 1_760_090_400_000L
    }
}

object Contract {
    val dir: File by lazy {
        val prop = System.getProperty("beconscient.contractDir")
        val candidates = listOfNotNull(prop, "../../contract", "../contract").map { File(it) }
        candidates.firstOrNull { File(it, "envelope.md").isFile }
            ?: error("contract directory not found (beconscient.contractDir=$prop)")
    }

    fun read(path: String): String = File(dir, path).readText()

    fun json(path: String): JsonObject = Json.parseToJsonElement(read(path)).jsonObject
}

/** The test-only key pair from contract/keys. Its private half is public on purpose. */
object TestKeys {
    private val pair by lazy { Contract.json("keys/test-keypair.json") }
    val kid: String get() = pair["kid"]!!.jsonPrimitive.content
    val publicKey: ByteArray get() = Base64Url.decode(pair["public_key"]!!.jsonPrimitive.content)
    val privateKey: ByteArray get() = Base64Url.decode(pair["private_key"]!!.jsonPrimitive.content)

    fun config(baseUrl: String) = ServerConfig(baseUrl.trimEnd('/'), kid, publicKey)
}

fun context(): Context = ApplicationProvider.getApplicationContext()

fun memoryDb(): EventDb =
    Room.inMemoryDatabaseBuilder(context(), EventDb::class.java).allowMainThreadQueries().build()

fun testCredentials(installationId: String = "0192f3a0-1c2b-7cde-8f00-112233445566") = Credentials(
    installationId = installationId,
    secret = ByteArray(32) { it.toByte() },
    deviceId = "0192f3a0-0000-7000-8000-0000000000aa",
    deviceName = "Test phone",
    fingerprint = "ABCD-EFGH",
    serverFingerprint = "ABCD-EFGH",
    pairedAt = FakeClock.T0,
)

class FakeProbe(
    var usage: Boolean = true,
    var notifications: Boolean = true,
    var listener: Boolean = true,
    var accessibility: Boolean = true,
    var shorts: Boolean = true,
    var battery: Boolean = true,
    var unusedOff: Boolean? = true,
    var home: String? = "com.google.android.apps.nexuslauncher",
) : SensorProbe {
    override fun usageAccess() = usage
    override fun notificationAccess() = notifications
    override fun listenerConnected() = listener
    override fun accessibilityEnabled() = accessibility
    override fun shortsRunning() = shorts
    override fun batteryExempt() = battery
    override fun unusedAppRestrictionsOff() = unusedOff
    override fun homePackage() = home
}

fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.content

fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

/** Polls [check] until it passes or [timeoutMs] elapses. For work handed to background scopes. */
fun eventually(timeoutMs: Long = 5_000, check: () -> Unit) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        val failure = try {
            check()
            return
        } catch (e: AssertionError) {
            e
        } catch (e: Exception) {
            e
        }
        if (System.currentTimeMillis() > deadline) throw failure
        Thread.sleep(25)
    }
}
