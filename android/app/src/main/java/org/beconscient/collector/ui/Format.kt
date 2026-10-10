package org.beconscient.collector.ui

import java.text.DateFormat
import java.util.Date

internal fun formatTime(ms: Long?): String =
    if (ms == null || ms <= 0) "never" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** Plain-language meaning of the error codes the uploader stores. */
internal fun explainUploadError(code: String?): String? = when {
    code == null -> null
    code == "network" -> "No connection to the server. Events wait on the phone and go out later."
    code == "unauthorized" -> "The server refused this installation (revoked or unknown). Pair again."
    code == "sequence_conflict" -> "The server saw a different batch with this number and flagged a possible clone. Pair again."
    code == "bad_response" || code == "bad_receipt" || code == "receipt_mismatch" ->
        "The answer did not come from your server (a captive portal or proxy?). Nothing was deleted; the batch will be resent."
    code == "rate_limited" -> "The server asked to slow down. Retrying later."
    code == "too_large" -> "A single event was too large for the server."
    code.startsWith("http_5") -> "The server is unavailable ($code). Retrying with growing delays."
    else -> "Upload failed ($code). Retrying later."
}
