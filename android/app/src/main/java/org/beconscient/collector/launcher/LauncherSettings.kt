package org.beconscient.collector.launcher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A stretch of the week with its own message, such as "19:00 to 20:00, Family".
 * An [end] at or before [start] runs past midnight and belongs to the day it
 * started, like the awake window on the page.
 */
data class Block(
    val id: String,
    val name: String,
    val days: Set<DayOfWeek>,
    val start: LocalTime,
    val end: LocalTime,
    val message: String,
    /** Package of the app the pause screen offers instead, or null. */
    val alternative: String? = null,
    /** While the block is in force the phone is in a focus period: only allowed apps open. */
    val locks: Boolean = false,
) {
    fun inForceAt(at: LocalDateTime): Boolean {
        val time = at.toLocalTime()
        val day = at.dayOfWeek
        return if (end.isAfter(start)) {
            day in days && !time.isBefore(start) && time.isBefore(end)
        } else {
            (day in days && !time.isBefore(start)) || (day.minus(1) in days && time.isBefore(end))
        }
    }

    /** When the occurrence of this block in force at [at] ends. */
    fun endAfter(at: LocalDateTime): LocalDateTime {
        val day = at.toLocalDate()
        return when {
            end.isAfter(start) -> day.atTime(end)
            at.toLocalTime().isBefore(end) -> day.atTime(end)
            else -> day.plusDays(1).atTime(end)
        }
    }

    companion object {
        const val NAME_MAX = 60
        const val MESSAGE_MAX = 200
    }
}

/** Everything the owner sets up on the launcher. It stays on the phone. */
data class LauncherSettings(
    val pinned: List<String> = emptyList(),
    val controlled: Set<String> = emptySet(),
    val blocks: List<Block> = emptyList(),
    /** Apps that open during a focus period. Null until the owner edits the list: the phone's defaults apply. */
    val focusAllowed: Set<String>? = null,
) {
    /** The first block in the owner's order that covers [at]. */
    fun blockAt(at: LocalDateTime): Block? = blocks.firstOrNull { it.inForceAt(at) }

    /** The first locking block in force at [at]. */
    fun lockingBlockAt(at: LocalDateTime): Block? = blocks.firstOrNull { it.locks && it.inForceAt(at) }

    fun encode(): String = buildJsonObject {
        put("version", 1)
        putJsonArray("pinned") { pinned.forEach { add(it) } }
        putJsonArray("controlled") { controlled.sorted().forEach { add(it) } }
        putJsonArray("blocks") {
            for (b in blocks) {
                add(buildJsonObject {
                    put("id", b.id)
                    put("name", b.name)
                    put("days", buildJsonArray { b.days.sorted().forEach { add(it.value) } })
                    put("start", b.start.toString())
                    put("end", b.end.toString())
                    put("message", b.message)
                    if (b.alternative != null) put("alternative", b.alternative)
                    if (b.locks) put("locks", true)
                })
            }
        }
        if (focusAllowed != null) putJsonArray("focus_allowed") { focusAllowed.sorted().forEach { add(it) } }
    }.toString()

    companion object {
        /** Reads what [encode] wrote. Anything unreadable falls back to empty settings rather than crashing the home screen. */
        fun decode(text: String?): LauncherSettings {
            if (text.isNullOrBlank()) return LauncherSettings()
            val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return LauncherSettings()
            return LauncherSettings(
                pinned = root.strings("pinned").distinct(),
                controlled = root.strings("controlled").toSet(),
                blocks = root.array("blocks").mapNotNull { runCatching { block(it.jsonObject) }.getOrNull() },
                focusAllowed = if (root["focus_allowed"] is JsonArray) root.strings("focus_allowed").toSet() else null,
            )
        }

        private fun block(o: JsonObject): Block? {
            val days = o.array("days").mapNotNull { it.jsonPrimitive.intOrNull?.takeIf { d -> d in 1..7 } }
                .map(DayOfWeek::of).toSet()
            val start = o.string("start")?.let(LocalTime::parse) ?: return null
            val end = o.string("end")?.let(LocalTime::parse) ?: return null
            return Block(
                id = o.string("id") ?: return null,
                name = o.string("name").orEmpty(),
                days = days,
                start = start,
                end = end,
                message = o.string("message").orEmpty(),
                alternative = o.string("alternative"),
                locks = (o["locks"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }

        private fun JsonObject.array(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())

        private fun JsonObject.strings(key: String): List<String> =
            array(key).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
