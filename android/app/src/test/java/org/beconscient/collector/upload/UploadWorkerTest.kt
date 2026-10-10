package org.beconscient.collector.upload

import androidx.work.BackoffPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.beconscient.collector.AppGraph
import org.beconscient.collector.events.Events
import org.beconscient.collector.pairing.InMemoryCredentialStore
import org.beconscient.collector.queue.EventDb
import org.beconscient.collector.queue.EventState
import org.beconscient.collector.support.FakeClock
import org.beconscient.collector.support.FakeProbe
import org.beconscient.collector.support.TestKeys
import org.beconscient.collector.support.context
import org.beconscient.collector.support.memoryDb
import org.beconscient.collector.support.testCredentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class UploadWorkerTest {
    private lateinit var db: EventDb
    private val clock = FakeClock()
    private var status = 503

    @Before
    fun setUp() {
        db = memoryDb()
        AppGraph.install(
            AppGraph(
                context(), clock = clock, db = db, config = TestKeys.config("http://test.invalid"),
                credentials = InMemoryCredentialStore(testCredentials()),
                transport = HttpTransport { _, _ -> HttpResult(status, "{\"error\":\"unavailable\"}") },
                probe = FakeProbe(),
            ),
        )
    }

    @After
    fun tearDown() {
        AppGraph.install(null)
        db.close()
    }

    @Test
    fun `a 5xx makes the worker retry and leaves the queue alone`() = runBlocking {
        db.dao().insertIgnore(Events.usage(clock.now, "SCREEN_INTERACTIVE", clock.now, null, null, null))
        val worker = TestListenableWorkerBuilder<UploadWorker>(context()).build()
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        assertEquals(1, db.dao().countByState(EventState.IN_BATCH))
    }

    @Test
    fun `uploads back off exponentially and wait for a network`() {
        val spec = Scheduling.uploadRequest().workSpec
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(Scheduling.UPLOAD_BACKOFF_SECONDS), spec.backoffDelayDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
    }

    @Test
    fun `collection is periodic every 15 minutes`() {
        val spec = Scheduling.periodicRequest().workSpec
        assertTrue(spec.isPeriodic)
        assertEquals(TimeUnit.MINUTES.toMillis(15), spec.intervalDuration)
    }
}
