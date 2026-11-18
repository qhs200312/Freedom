package com.v2ray.ang.root

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

class RootProcessRunnerTest {
    private fun process(output: String): Process = mock(Process::class.java).also {
        `when`(it.inputStream).thenReturn(ByteArrayInputStream(output.toByteArray()))
    }

    @Test
    fun completedCommandRetainsExitStatusAndBoundsVerboseRootOutput() {
        val process = process("x".repeat(200_000))
        `when`(process.waitFor(1, TimeUnit.SECONDS)).thenReturn(true)
        `when`(process.exitValue()).thenReturn(7)
        val result = RootProcessRunner.await(process, 1, TimeUnit.SECONDS)
        assertEquals(7, result.code)
        assertEquals(65_536, result.output.length)
        verify(process, never()).destroyForcibly()
    }

    @Test
    fun timeoutDestroysTheSuCommandRatherThanLeavingAnUnmanagedProcess() {
        val process = process("waiting")
        `when`(process.waitFor(1, TimeUnit.MILLISECONDS)).thenReturn(false)
        assertEquals(-1, RootProcessRunner.await(process, 1, TimeUnit.MILLISECONDS).code)
        verify(process).destroyForcibly()
    }

    @Test
    fun interruptionDestroysTheCommandAndPreservesTheInterruptFlag() {
        val process = process("")
        `when`(process.waitFor(1, TimeUnit.SECONDS)).thenThrow(InterruptedException())
        try {
            RootProcessRunner.await(process, 1, TimeUnit.SECONDS)
            fail()
        } catch (_: InterruptedException) {
            assertTrue(Thread.currentThread().isInterrupted)
            verify(process).destroyForcibly()
        } finally { Thread.interrupted() }
    }
}
