package org.beconscient.collector.upload

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.beconscient.collector.pairing.Credentials
import org.beconscient.collector.util.Base64Url
import java.util.TimeZone

/** The batch plaintext of contract/envelope.md: a header and the stored event JSON, unchanged. */
object BatchPlaintext {
    const val CONTRACT_VERSION = 1

    fun build(
        credentials: Credentials,
        seq: Long,
        sentAt: Long,
        zone: TimeZone,
        clientVersion: String,
        eventPayloads: List<String>,
    ): String {
        val header = buildJsonObject {
            put("installation_id", credentials.installationId)
            put("credential", Base64Url.encode(credentials.secret))
            put("seq", seq)
            put("sent_at", sentAt)
            put("tz", zone.id)
            put("tz_offset_min", zone.getOffset(sentAt) / 60_000)
            put("contract_version", CONTRACT_VERSION)
            put("client_version", clientVersion)
        }
        val sb = StringBuilder(eventPayloads.sumOf { it.length + 1 } + 512)
        sb.append("{\"header\":").append(header.toString()).append(",\"events\":[")
        eventPayloads.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append(p)
        }
        sb.append("]}")
        return sb.toString()
    }
}
