package org.beconscient.collector.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Runs submitted jobs one at a time, in submission order. Callbacks from system services use it. */
class SerialQueue(scope: CoroutineScope, private val tag: String) {
    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in jobs) {
                try {
                    job()
                } catch (t: Throwable) {
                    // Never log event content: only the failure type.
                    Log.w(tag, "job failed: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    fun submit(job: suspend () -> Unit) {
        jobs.trySend(job)
    }
}
