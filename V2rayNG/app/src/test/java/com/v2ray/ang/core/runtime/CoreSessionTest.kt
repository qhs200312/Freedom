package com.v2ray.ang.core.runtime

import com.v2ray.ang.core.RuntimeCore
import com.v2ray.ang.dto.OutboundTrafficStat
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CoreSessionTest {
    private class FakeCore(override val type: RuntimeCore = RuntimeCore.SING_BOX) : CoreRuntime {
        override var isRunning = false
        var starts = 0
        var stops = 0
        var probes = 0
        var startAction: () -> Unit = {}
        var stopAction: () -> Unit = {}
        override fun start() { starts++; startAction(); isRunning = true }
        override fun stop() { stops++; stopAction(); isRunning = false }
        override fun measureDelay(url: String): Long { probes++; return -1 }
        override fun trafficStats() = emptyList<OutboundTrafficStat>()
    }

    @Test
    fun startupDoesNotRequirePublicWebsiteAvailability() {
        val session = CoreSession()
        val core = FakeCore()
        val ticket = session.start { core }!!
        assertTrue(session.isRunning)
        assertFalse(session.isReady)
        assertTrue(session.markReady(ticket))
        assertEquals(0, core.probes)
        assertEquals(-1, session.measureDelay(ticket, "https://example.invalid"))
        assertTrue(session.isReady)
    }

    @Test
    fun selectedRuntimeIsTheOnlyRuntimeCreatedAndStopped() {
        val session = CoreSession()
        val singBox = FakeCore()
        val xray = FakeCore(RuntimeCore.XRAY)
        session.start { singBox }
        assertEquals(0, xray.starts)
        session.start { xray }
        assertEquals(1, singBox.stops)
        assertEquals(1, xray.starts)
        session.finishStop(session.requestStop()!!)
        assertFalse(session.isRunning)
        assertEquals(1, xray.stops)
    }

    @Test
    fun lateDestroyOfOldServiceCannotStopReplacement() {
        val session = CoreSession()
        val oldOwner = Any()
        val newOwner = Any()
        val old = FakeCore()
        val replacement = FakeCore()
        session.start(oldOwner) { old }
        val ticket = session.start(newOwner) { replacement }!!
        assertNull(session.requestStop(oldOwner))
        session.retire(oldOwner)
        assertTrue(session.markReady(ticket))
        assertEquals(0, replacement.stops)
    }

    @Test
    fun staleReadinessAndRecoveryCannotReviveStoppedCore() {
        val session = CoreSession()
        val core = FakeCore()
        val ticket = session.start { core }!!
        session.markReady(ticket)
        val stop = session.requestStop()!!
        assertFalse(session.markReady(ticket))
        assertFalse(session.reload(ticket))
        session.finishStop(stop)
        assertEquals(1, core.starts)
        assertEquals(CoreSessionState.STOPPED, session.state)
    }

    @Test
    fun failureClosesPartiallyStartedCore() {
        val session = CoreSession()
        val core = FakeCore().apply { startAction = { throw IllegalStateException("bad configuration") } }
        try {
            session.start { core }
            fail("must fail")
        } catch (_: IllegalStateException) {
            assertEquals(1, core.stops)
            assertNull(session.runtime)
            assertEquals(CoreSessionState.STOPPED, session.state)
        }
    }

    @Test
    fun stopDuringBlockingStartupPreventsSuccess() {
        val session = CoreSession()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val core = FakeCore().apply {
            startAction = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val start = executor.submit<Long?> { session.start { core } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val stop = session.requestStop()!!
            release.countDown()
            assertNull(start.get(3, TimeUnit.SECONDS))
            session.finishStop(stop)
            assertEquals(1, core.stops)
            assertFalse(session.isRunning)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun stopDuringReloadDoesNotRestartCore() {
        val session = CoreSession()
        val core = FakeCore()
        val ticket = session.start { core }!!
        session.markReady(ticket)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        core.stopAction = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reload = executor.submit<Boolean> { session.reload(ticket) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val stop = session.requestStop()!!
            release.countDown()
            assertFalse(reload.get(3, TimeUnit.SECONDS))
            session.finishStop(stop)
            assertEquals(1, core.starts)
            assertFalse(session.isReady)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun nativeStopFinishesBeforeReplacementStarts() {
        val session = CoreSession()
        val events = mutableListOf<String>()
        session.start { FakeCore().apply { stopAction = { events += "old-stopped" } } }
        session.start { FakeCore().apply { startAction = { events += "new-started" } } }
        assertEquals(listOf("old-stopped", "new-started"), events)
    }

    @Test
    fun lateXrayNetworkCallbackCannotRestartSingBox() {
        val session = CoreSession()
        val xray = FakeCore(RuntimeCore.XRAY)
        val oldTicket = session.start { xray }!!
        session.markReady(oldTicket)
        val singBox = FakeCore()
        val newTicket = session.start { singBox }!!
        session.markReady(newTicket)
        assertFalse(session.reload(oldTicket))
        assertEquals(1, singBox.starts)
        assertEquals(0, singBox.stops)
        assertTrue(session.isReady)
    }

    @Test
    fun pendingOldStopCannotStopNewRuntime() {
        val session = CoreSession()
        val oldOwner = Any()
        session.start(oldOwner) { FakeCore() }
        val oldStop = session.requestStop(oldOwner)!!
        val newCore = FakeCore()
        val ticket = session.start(Any()) { newCore }!!
        session.finishStop(oldStop)
        session.retire(oldOwner)
        assertTrue(session.markReady(ticket))
        assertEquals(0, newCore.stops)
    }

    @Test
    fun unexpectedNativeExitCanBeRecoveredWithoutNewSession() {
        val session = CoreSession()
        val core = FakeCore(RuntimeCore.XRAY)
        val ticket = session.start { core }!!
        session.markReady(ticket)
        core.isRunning = false
        assertFalse(session.isReady)
        assertTrue(session.reload(ticket))
        assertTrue(session.isReady)
        assertEquals(2, core.starts)
    }
}
