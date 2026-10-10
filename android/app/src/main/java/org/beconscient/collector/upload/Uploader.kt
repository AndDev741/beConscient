package org.beconscient.collector.upload

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.beconscient.collector.crypto.Envelope
import org.beconscient.collector.crypto.Hpke
import org.beconscient.collector.pairing.CredentialStore
import org.beconscient.collector.pairing.Credentials
import org.beconscient.collector.queue.EventDao
import org.beconscient.collector.queue.Kv
import org.beconscient.collector.util.Clock
import java.io.IOException
import java.util.TimeZone

/**
 * Sends the queue in frozen batches (contract/envelope.md, "Sequence numbers and
 * resends"). A batch is frozen before its first send and resent unchanged until
 * a receipt opens with this request's exported key and names this installation
 * and sequence. Only ids from that batch are acted on.
 */
class Uploader(
    private val dao: EventDao,
    private val credentials: CredentialStore,
    private val transport: HttpTransport,
    private val config: ServerConfig,
    private val clock: Clock,
    private val clientVersion: String,
    private val zone: () -> TimeZone = { TimeZone.getDefault() },
) {
    sealed interface Outcome {
        data object NotPaired : Outcome
        data class Done(val batches: Int) : Outcome
        /** Something failed; nothing in the queue changed. Try again later with backoff. */
        data class Retry(val reason: String, val batches: Int) : Outcome
    }

    private sealed interface Step {
        data object Empty : Step
        data object Sent : Step
        data object Again : Step
        data class Failed(val reason: String) : Step
    }

    suspend fun uploadAll(maxBatches: Int = 25): Outcome = lock.withLock {
        val creds = credentials.load() ?: return Outcome.NotPaired
        var sent = 0
        var attempts = 0
        while (attempts < maxBatches) {
            attempts++
            when (val step = sendOne(creds)) {
                Step.Empty -> return Outcome.Done(sent)
                Step.Sent -> sent++
                Step.Again -> Unit
                is Step.Failed -> {
                    dao.putString(Kv.UPLOAD_LAST_ERROR, step.reason)
                    return Outcome.Retry(step.reason, sent)
                }
            }
        }
        Outcome.Done(sent)
    }

    private suspend fun sendOne(creds: Credentials): Step {
        val now = clock.now()
        var batch = dao.openBatch()
        if (batch != null && batch.installationId != creds.installationId) {
            dao.resetForNewInstallation()
            batch = null
        }
        if (batch == null) {
            val seq = dao.long(Kv.NEXT_SEQ) ?: 1L
            val maxEvents = dao.long(Kv.UPLOAD_MAX_EVENTS)?.toInt() ?: MAX_EVENTS
            batch = dao.freeze(seq, creds.installationId, now, maxEvents, MAX_PAYLOAD_CHARS) ?: return Step.Empty
        }
        val seq = batch.seq
        val items = dao.batchItems(seq)
        val events = dao.batchEvents(seq)
        if (events.size != items.size || events.map { it.id } != items.map { it.eventId }) {
            // Cannot happen while the queue is used through EventDao; refuse to send a changed list.
            dao.dropUnsentBatch(seq)
            return Step.Failed("frozen_batch_damaged")
        }

        val plaintext = BatchPlaintext.build(creds, seq, now, zone(), clientVersion, events.map { it.payload })
        val request = Envelope.seal(config.publicKey, config.kid, Hpke.INFO_BATCH, plaintext)
        dao.recordAttempt(seq, now)
        dao.putLong(Kv.UPLOAD_LAST_ATTEMPT_AT, now)

        val response = try {
            withContext(Dispatchers.IO) { transport.postJson(config.batchUrl, request.body) }
        } catch (e: IOException) {
            return Step.Failed("network")
        }

        return when (response.code) {
            200 -> applyResponse(creds, seq, items.map { it.eventId }, request, response.body)
            413 -> if (items.size > 1) {
                // Never logged by the server (refused before decryption): re-freeze smaller under the same seq.
                dao.dropUnsentBatch(seq)
                dao.putLong(Kv.UPLOAD_MAX_EVENTS, (items.size / 2).toLong())
                Step.Again
            } else {
                Step.Failed("too_large")
            }
            401 -> Step.Failed("unauthorized")
            409 -> Step.Failed("sequence_conflict")
            429 -> Step.Failed("rate_limited")
            else -> Step.Failed("http_${response.code}")
        }
    }

    private suspend fun applyResponse(
        creds: Credentials,
        seq: Long,
        batchIds: List<String>,
        request: Envelope.Request,
        body: String?,
    ): Step {
        val text = try {
            Envelope.openResponse(request.responseKey, body)
        } catch (e: Envelope.BadResponse) {
            return Step.Failed("bad_response")
        }
        val receipt = Receipt.parse(text) ?: return Step.Failed("bad_receipt")
        if (receipt.installationId != creds.installationId || receipt.seq != seq) return Step.Failed("receipt_mismatch")

        val inBatch = batchIds.toHashSet()
        val acked = ArrayList<String>()
        val rejected = LinkedHashMap<String, String?>()
        for (r in receipt.results) {
            if (r.id !in inBatch) continue
            when (r.status) {
                Receipt.ACCEPTED, Receipt.DUPLICATE -> acked += r.id
                Receipt.REJECTED -> rejected[r.id] = r.reason
            }
        }
        dao.applyReceipt(seq, acked, rejected, clock.now())
        return Step.Sent
    }

    companion object {
        const val MAX_EVENTS = 2000
        /** Event JSON per batch; base64 and the header keep the body well under the 1 MiB limit. */
        const val MAX_PAYLOAD_CHARS = 600 * 1024

        private val lock = Mutex()
    }
}
