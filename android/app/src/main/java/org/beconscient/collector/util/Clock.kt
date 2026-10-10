package org.beconscient.collector.util

/** Wall clock in epoch milliseconds. Injected so tests control time. */
fun interface Clock {
    fun now(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}
