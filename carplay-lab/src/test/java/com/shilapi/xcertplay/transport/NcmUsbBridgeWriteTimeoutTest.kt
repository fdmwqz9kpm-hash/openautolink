package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

/** Actual production bridge; only the Android USB boundary is scripted. */
class NcmUsbBridgeWriteTimeoutTest {
    private class Fixture(vararg results: Int, diagnostic: (String) -> Unit = {}) {
        val connection = mock(UsbDeviceConnection::class.java)
        val timeouts = mutableListOf<Int>()
        val blocks = mutableListOf<ByteArray>()
        private val remaining = java.util.ArrayDeque(results.toList())
        val bridge: NcmUsbBridge
        init {
            `when`(connection.bulkTransfer(any(UsbEndpoint::class.java), any(ByteArray::class.java), anyInt(), anyInt()))
                .thenAnswer { call ->
                    timeouts += call.getArgument<Int>(3)
                    blocks += call.getArgument<ByteArray>(1).copyOf()
                    if (remaining.isEmpty()) call.getArgument<Int>(2) else remaining.removeFirst()
                }
            bridge = NcmUsbBridge(connection, mock(UsbEndpoint::class.java), mock(UsbEndpoint::class.java), null, emptyList(), null, diagnostic)
        }
    }

    @Test fun boundsStartupUntilFirstFullTransferThenRestoresCallerEvenAfterFailure() {
        val f = Fixture(-1, 0, 31, -1)
        repeat(3) { f.bridge.send(byteArrayOf(1, 2, 3), 2000) }
        f.bridge.send(byteArrayOf(4), 731)
        f.bridge.send(byteArrayOf(5), 947)
        assertEquals(listOf(20, 20, 20, 731, 947), f.timeouts)
        f.bridge.close()
    }

    @Test fun neverExtendsShortCallerAndNeverReentersStartupAfterSuccess() {
        val f = Fixture(-1)
        f.bridge.send(byteArrayOf(1), 5)
        f.bridge.send(byteArrayOf(2), 7)
        f.bridge.send(byteArrayOf(3), 2000)
        assertEquals(listOf(5, 7, 2000), f.timeouts)
        f.bridge.close()
    }

    @Test fun partialTransferIsRejectedAndDoesNotEstablishReadiness() {
        val f = Fixture(1)
        assertThrows(IphoneUsbException.DeviceUnavailable::class.java) { f.bridge.send(byteArrayOf(1), 2000) }
        f.bridge.send(byteArrayOf(2), 2000)
        assertEquals(listOf(20, 20), f.timeouts)
        f.bridge.close()
    }

    @Test fun numericStartupDiagnosticsAreBoundedButAlwaysReceiptFirstFullSuccess() {
        val receipts = mutableListOf<String>()
        val f = Fixture(*IntArray(12) { -1 }, diagnostic = receipts::add)
        repeat(30) { f.bridge.send(byteArrayOf(1, 2, 3), 2000) }
        assertEquals("first four attempts plus first full accepted write only", 5, receipts.size)
        val pattern = Regex("NCM startup attempt=([0-9]+) timeoutMs=([0-9]+) transferred=(-?[0-9]+) blockBytes=([0-9]+) ready=([01]) writeStartMonoNs=([0-9]+) writeElapsedUs=([0-9]+) sinceOpenUs=([0-9]+)")
        val parsed = receipts.map { assertTrue(it, pattern.matches(it)); pattern.matchEntire(it)!!.groupValues.drop(1).map(String::toLong) }
        assertEquals(listOf(1L, 2L, 3L, 4L, 13L), parsed.map { it[0] })
        assertEquals(listOf(0L, 0L, 0L, 0L, 1L), parsed.map { it[4] })
        assertTrue(parsed.all { it[1] == 20L && it[5] > 0 && it[6] >= 0 && it[7] >= 0 })
        f.bridge.close()
    }

    @Test fun diagnosticFailureCannotChangeDropReadinessPartialFailureOrClose() {
        val f = Fixture(-1, 1, diagnostic = { throw IllegalStateException("diagnostic sink failed") })
        f.bridge.send(byteArrayOf(1), 2000)
        assertThrows(IphoneUsbException.DeviceUnavailable::class.java) { f.bridge.send(byteArrayOf(2), 2000) }
        f.bridge.send(byteArrayOf(3), 2000)
        f.bridge.send(byteArrayOf(4), 451)
        assertEquals(listOf(20, 20, 20, 451), f.timeouts)
        f.bridge.close(); verify(f.connection).close()
    }

    @Test fun fullTransferReturningAfterCloseCannotPublishReadinessOrDiagnostics() {
        val receipts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val f = Fixture(diagnostic = receipts::add)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        `when`(f.connection.bulkTransfer(any(UsbEndpoint::class.java), any(ByteArray::class.java), anyInt(), anyInt()))
            .thenAnswer { call ->
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                call.getArgument<Int>(2)
            }
        try {
            val sender = executor.submit<Throwable?> {
                try { f.bridge.send(byteArrayOf(1), 2000); null } catch (error: Throwable) { error }
            }
            assertTrue("production bulkTransfer entered", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Must retire without waiting for native USB or the sender's writeLock.
            executor.submit { f.bridge.close() }.get(5, java.util.concurrent.TimeUnit.SECONDS)
            verify(f.connection).close()
            release.countDown()
            val error = sender.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("retired completion emitted stale diagnostics: $receipts", receipts.isEmpty())
            assertTrue("retired completion must fail, got $error", error is IphoneUsbException.DeviceUnavailable)
            val accepted = NcmUsbBridge::class.java.getDeclaredField("bulkOutAccepted").apply { isAccessible = true }
            assertFalse("retired transfer must not establish readiness", accepted.getBoolean(f.bridge))
        } finally {
            release.countDown()
            executor.shutdownNow()
            f.bridge.close()
        }
    }

    @Test fun fullTransferReturningAfterReadFailureCannotPublishReadinessOrDiagnostics() {
        val receipts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val f = Fixture(diagnostic = receipts::add)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        `when`(f.connection.bulkTransfer(any(UsbEndpoint::class.java), any(ByteArray::class.java), anyInt(), anyInt()))
            .thenAnswer { call ->
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                call.getArgument<Int>(2)
            }
        try {
            val sender = executor.submit<Throwable?> {
                try { f.bridge.send(byteArrayOf(1), 2000); null } catch (error: Throwable) { error }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Fail the actual production read path at the Android request-initialization boundary.
            mockConstruction(android.hardware.usb.UsbRequest::class.java).use {
                val failure = assertThrows(IphoneUsbException.DeviceUnavailable::class.java) { f.bridge.recv(100) }
                release.countDown()
                val error = sender.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertTrue("failed completion emitted stale diagnostics: $receipts", receipts.isEmpty())
                assertSame("send must observe the exact retained read failure", failure, error)
                val accepted = NcmUsbBridge::class.java.getDeclaredField("bulkOutAccepted").apply { isAccessible = true }
                assertFalse(accepted.getBoolean(f.bridge))
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
            f.bridge.close()
        }
    }

    @Test fun admittedDiagnosticCanCloseFromAnotherThreadWithoutStateLockDeadlock() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        lateinit var f: Fixture
        var callbackClosed = false
        try {
            f = Fixture(diagnostic = {
                executor.submit { f.bridge.close() }.get(5, java.util.concurrent.TimeUnit.SECONDS)
                callbackClosed = true
            })
            f.bridge.send(byteArrayOf(1), 2000)
            assertTrue("diagnostic must run without holding stateLock", callbackClosed)
            assertThrows(IphoneUsbException.DeviceUnavailable::class.java) { f.bridge.send(byteArrayOf(2), 2000) }
            verify(f.connection).close()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun droppedPacketsAreNotReplayedAndNtbFramingSequenceRemainExact() {
        val f = Fixture(-1, 0)
        val frames = listOf(byteArrayOf(1), ByteArray(484) { 2 }, byteArrayOf(3))
        frames.forEach { f.bridge.send(it, 2000) }
        assertEquals(3, f.blocks.size)
        frames.forEachIndexed { index, frame -> assertArrayEquals(Ntb16Codec.build(frame, index), f.blocks[index]) }
        f.bridge.close()
        assertThrows(IphoneUsbException.DeviceUnavailable::class.java) { f.bridge.send(byteArrayOf(4), 2000) }
        assertEquals(3, f.blocks.size)
        verify(f.connection, times(1)).close()
    }
}
