package org.beconscient.collector.upload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A decrypted batch receipt. */
data class Receipt(val installationId: String, val seq: Long, val results: List<Result>) {
    data class Result(val id: String, val status: String, val reason: String?)

    companion object {
        const val ACCEPTED = "accepted"
        const val DUPLICATE = "duplicate"
        const val REJECTED = "rejected"

        /** Returns null for anything that is not a well-formed receipt. */
        fun parse(text: String): Receipt? = try {
            val obj = Json.parseToJsonElement(text) as JsonObject
            val installation = (obj["installation_id"] as JsonPrimitive).contentOrNull ?: return null
            val seq = (obj["seq"] as JsonPrimitive).longOrNull ?: return null
            val results = (obj["results"] as JsonArray).mapNotNull { el ->
                val r = el as? JsonObject ?: return@mapNotNull null
                val id = (r["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val status = (r["status"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                Result(id, status, (r["reason"] as? JsonPrimitive)?.contentOrNull)
            }
            Receipt(installation, seq, results)
        } catch (e: Exception) {
            null
        }
    }
}
