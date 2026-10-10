package org.beconscient.collector.upload

import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.beconscient.collector.AppGraph
import org.beconscient.collector.events.Events
import org.beconscient.collector.pairing.InMemoryCredentialStore
import org.beconscient.collector.queue.EventDb
import org.beconscient.collector.support.FakeClock
import org.beconscient.collector.support.FakeProbe
import org.beconscient.collector.support.TestKeys
import org.beconscient.collector.support.context
import org.beconscient.collector.support.memoryDb
import org.beconscient.collector.support.parse
import org.beconscient.collector.support.str
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CollectWorkerTest {
    private lateinit var db: EventDb
    private val clock = FakeClock()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context())
        db = memoryDb()
        AppGraph.install(
            AppGraph(
                context(), clock = clock, db = db, config = TestKeys.config("http://test.invalid"),
                credentials = InMemoryCredentialStore(), probe = FakeProbe(usage = false),
            ),
        )
    }

    @After
    fun tearDown() {
        AppGraph.install(null)
        db.close()
    }

    @Test
    fun `without usage access the run still writes a health record showing usage stopped and asks for an upload`() = runBlocking {
        val worker = TestListenableWorkerBuilder<CollectWorker>(context()).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())

        val health = parse(db.dao().eventsOfType(Events.HEALTH).single().payload)
        val usage = health["sensors"]!!.jsonObject["usage"]!!.jsonObject
        assertEquals("stopped", usage.str("state"))
        assertTrue(db.dao().eventsOfType(Events.USAGE).isEmpty())
        val upload = WorkManager.getInstance(context()).getWorkInfosForUniqueWork(Scheduling.UPLOAD).get()
        assertEquals(WorkInfo.State.ENQUEUED, upload.single().state)
    }

    @Test
    fun `a second run within five minutes adds no extra health record`() = runBlocking {
        TestListenableWorkerBuilder<CollectWorker>(context()).build().doWork()
        clock.advance(60_000)
        TestListenableWorkerBuilder<CollectWorker>(context()).build().doWork()
        assertEquals(1, db.dao().eventsOfType(Events.HEALTH).size)
        clock.advance(15 * 60_000)
        TestListenableWorkerBuilder<CollectWorker>(context()).build().doWork()
        assertEquals(2, db.dao().eventsOfType(Events.HEALTH).size)
    }
}
