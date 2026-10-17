package com.openautolink.app.carplay

import android.content.*
import android.hardware.usb.*
import android.net.VpnService
import android.view.Surface
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.shilapi.xcertplay.transport.IphoneUsbException
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/** Runs the production permission -> descriptor -> vendor request -> USBMUX-open path.
 * Only Android's USB/consent boundary is substituted; mode policy and permission waiter are real. */
class UsbWiredModeRuntimeTest {
    @get:org.junit.Rule val temp = org.junit.rules.TemporaryFolder()
    @After fun cleanup() = unmockkAll()
    private val usb = mockk<UsbManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val connection = mockk<UsbDeviceConnection>(relaxed = true)
    private val receipts = mutableListOf<String>()
    private val accepted = mutableListOf<UsbDevice>()
    private var admission: (UsbDevice) -> Boolean = { true }
    private val receivers = mutableListOf<BroadcastReceiver>()
    private var current = hashMapOf<String, UsbDevice>()
    private fun detach(device: UsbDevice) {
        current.remove(device.deviceName)
        val intent = mockk<Intent> {
            every { action } returns UsbManager.ACTION_USB_DEVICE_DETACHED
            every { getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) } returns device
        }
        receivers.toList().forEach { it.onReceive(context, intent) }
    }
    private fun phone(ethernet: Boolean, path: String = "/dev/bus/usb/001/002", includeNcm: Boolean = true): UsbDevice {
        val mux = mockk<UsbInterface>(relaxed = true) { every { id } returns 1; every { interfaceClass } returns 255; every { interfaceSubclass } returns 254; every { interfaceProtocol } returns 2 }
        val ncm = mockk<UsbInterface>(relaxed = true) { every { id } returns 2; every { interfaceClass } returns 2; every { interfaceSubclass } returns 13; every { interfaceProtocol } returns 0 }
        val input = mockk<UsbEndpoint> { every { direction } returns 128; every { type } returns 2 }
        val output = mockk<UsbEndpoint> { every { direction } returns 0; every { type } returns 2 }
        val data = mockk<UsbInterface>(relaxed = true) {
            every { id } returns 3; every { alternateSetting } returns 1; every { interfaceClass } returns 10
            every { endpointCount } returns 2; every { getEndpoint(0) } returns input; every { getEndpoint(1) } returns output
        }
        val eth = mockk<UsbInterface>(relaxed = true) { every { interfaceClass } returns 255; every { interfaceSubclass } returns 253; every { interfaceProtocol } returns 1 }
        val interfaces = listOf(mux) + (if (includeNcm) listOf(ncm, data) else emptyList()) + (if (ethernet) listOf(eth) else emptyList())
        val config = mockk<UsbConfiguration> { every { id } returns 5; every { interfaceCount } returns interfaces.size; every { getInterface(any()) } answers { interfaces[firstArg()] } }
        return mockk { every { deviceName } returns path; every { deviceId } returns path.substringAfterLast('/').toInt(); every { vendorId } returns 0x05ac; every { productId } returns 0x12a8; every { serialNumber } returns "synthetic-same-phone"; every { configurationCount } returns 1; every { getConfiguration(0) } returns config }
    }
    private fun setup(device: UsbDevice): OalUsbCarPlayRuntime {
        every { context.applicationContext } returns context
        every { context.packageName } returns "com.openautolink.app"
        every { context.getSystemService(UsbManager::class.java) } returns usb
        current = hashMapOf(device.deviceName to device)
        every { usb.deviceList } answers { HashMap(current) }
        every { context.registerReceiver(any(), any<IntentFilter>(), any<Int>()) } answers { receivers.add(firstArg()); null }
        every { context.unregisterReceiver(any()) } answers { receivers.remove(firstArg()); Unit }
        every { usb.hasPermission(any<UsbDevice>()) } returns true
        every { usb.openDevice(any()) } returns null
        mockkStatic(VpnService::class)
        every { VpnService.prepare(context) } returns null
        return OalUsbCarPlayRuntime(context, device, CarPlayDisplayPreferences(), mockk<Surface>(), receipts::add,
            onReenumerationStarted = { true }, onReplacementAccepted = { accepted.add(it); admission(it) })
    }
    private fun request(device: UsbDevice, result: Int = -1, during: () -> Unit = {}) {
        every { usb.openDevice(device) } returnsMany listOf(connection, null)
        every { connection.controlTransfer(any(), any(), any(), any(), any<ByteArray>(), any(), any()) } answers { during(); result }
    }
    private fun failure(runtime: OalUsbCarPlayRuntime): Throwable {
        val run = runtime.javaClass.getDeclaredMethod("runConnection").apply { isAccessible = true }
        return try { run.invoke(runtime); AssertionError("USBMUX sentinel must stop the fixture") }
        catch (error: InvocationTargetException) { error.targetException }
    }
    private fun runToOpen(runtime: OalUsbCarPlayRuntime) {
        val error = failure(runtime)
        assertTrue(error.toString(), error is IphoneUsbException.DeviceUnavailable)
    }
    @Test fun readyDescriptorsReuseWithZeroVendorRequests() {
        val device = phone(true); val runtime = setup(device)
        try {
            runToOpen(runtime)
            verify(exactly = 1) { usb.openDevice(device) }
            verify(exactly = 0) { connection.controlTransfer(any(), any(), any(), any(), any<ByteArray>(), any(), any()) }
            assertTrue(accepted.isEmpty())
        } finally { runtime.close() }
    }
    @Test fun acceptedTransitionUsesReplacementFallbackWithoutSecondRequest() {
        val device = phone(false); val replacement = phone(false, "/dev/bus/usb/001/003")
        val runtime = setup(device)
        request(device, 1) { detach(device); current[replacement.deviceName] = replacement }
        try {
            runToOpen(runtime)
            assertEquals(listOf(replacement), accepted)
            verify(exactly = 1) { usb.openDevice(device) }
            verify(exactly = 1) { usb.openDevice(replacement) }
            verify(exactly = 1) { connection.controlTransfer(any(), any(), any(), any(), any<ByteArray>(), any(), any()) }
        } finally { runtime.close() }
    }
    @Test fun rejectionAfterDeliveredDetachNeverFallsBack() {
        val device = phone(false); val runtime = setup(device)
        request(device) { detach(device); current[device.deviceName] = device }
        try {
            assertTrue(failure(runtime) is IphoneUsbException.Protocol)
            assertTrue(accepted.isEmpty())
            verify(exactly = 1) { usb.openDevice(device) }
        } finally { runtime.close() }
    }
    @Test fun rejectionAfterLostPermissionNeverFallsBack() {
        val device = phone(false); val runtime = setup(device)
        request(device) { every { usb.hasPermission(device) } returns false }
        try {
            assertTrue(failure(runtime) is IphoneUsbException.Protocol)
            assertTrue(accepted.isEmpty())
            verify(exactly = 1) { usb.openDevice(device) }
        } finally { runtime.close() }
    }
    @Test fun rejectedRequestCannotOpenAfterCancellationWhileNativeTransferWasBlocked() {
        val device = phone(false); val runtime = setup(device)
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        request(device) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        try {
            val result = pool.submit<Throwable> { failure(runtime) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            runtime.close(); release.countDown()
            assertTrue(result.get(5, java.util.concurrent.TimeUnit.SECONDS) is IllegalStateException)
            assertTrue(accepted.isEmpty())
            verify(exactly = 1) { usb.openDevice(device) }
            verify(exactly = 1) { connection.close() }
        } finally { release.countDown(); pool.shutdownNow(); runtime.close() }
    }
    @Test fun acceptedRequestWithoutReplacementExpiresWithoutOpeningOldFallback() {
        val device = phone(false); val runtime = setup(device)
        mockkStatic(android.os.SystemClock::class)
        every { android.os.SystemClock.elapsedRealtime() } returnsMany listOf(0L, 15_000L)
        request(device, 1)
        try {
            assertTrue(failure(runtime) is java.util.concurrent.TimeoutException)
            assertTrue(accepted.isEmpty())
            verify(exactly = 1) { usb.openDevice(device) }
        } finally { runtime.close() }
    }
    @Test fun acceptedTransitionRejectsAmbiguousReplacementWithoutOpeningEither() {
        val device = phone(false); val a = phone(true, "/dev/bus/usb/001/003"); val b = phone(true, "/dev/bus/usb/001/004")
        val runtime = setup(device)
        request(device, 1) { detach(device); current[a.deviceName] = a; current[b.deviceName] = b }
        try {
            assertTrue(failure(runtime) is IllegalStateException)
            assertTrue(accepted.isEmpty())
            verify(exactly = 1) { usb.openDevice(any()) }
        } finally { runtime.close() }
    }
    @Test fun replacementWithDifferentPrivateSerialCannotBeAdmitted() {
        val device = phone(false); val replacement = phone(true, "/dev/bus/usb/001/003")
        every { replacement.serialNumber } returns "synthetic-other-phone"
        val runtime = setup(device)
        request(device, 1) { detach(device); current[replacement.deviceName] = replacement }
        try {
            assertTrue(failure(runtime) is IllegalStateException)
            assertTrue(accepted.isEmpty())
            verify(exactly = 0) { usb.openDevice(replacement) }
        } finally { runtime.close() }
    }
    @Test fun rejectionCannotReuseConfigurationThatDisappearedFromCurrentCensus() {
        val device = phone(false); val changed = phone(false, includeNcm = false); val runtime = setup(device)
        request(device) { current[device.deviceName] = changed }
        try {
            assertTrue("No current valid fallback may reach USBMUX", failure(runtime) is IphoneUsbException.Protocol)
            assertTrue(accepted.isEmpty())
            verify(exactly = 1) { usb.openDevice(device) }
        } finally { runtime.close() }
    }
    @Test fun missingConfigurationRejectedRequestFailsClosed() {
        val device = phone(false, includeNcm = false); val runtime = setup(device)
        request(device)
        try {
            assertTrue(failure(runtime) is IphoneUsbException.Protocol)
            assertTrue(accepted.isEmpty())
            verify(exactly = 1) { usb.openDevice(device) }
        } finally { runtime.close() }
    }
    private fun freshPermission(replacement: UsbDevice, afterRequest: () -> Unit = {}) {
        var granted = false
        every { usb.hasPermission(replacement) } answers { granted }
        mockkStatic(android.app.PendingIntent::class)
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra("generation", any<Long>()) } answers { self as Intent }
        every { android.app.PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk(relaxed = true)
        every { usb.requestPermission(replacement, any<android.app.PendingIntent>()) } answers { granted = true; afterRequest() }
    }
    @Test fun acceptedTransitionRequiresFreshRealReplacementPermissionThenAdmitsOnce() {
        val device = phone(false); val replacement = phone(true, "/dev/bus/usb/001/003"); val runtime = setup(device)
        freshPermission(replacement)
        request(device, 1) { detach(device); current[replacement.deviceName] = replacement }
        try {
            runToOpen(runtime)
            assertEquals(listOf(replacement), accepted)
            verify(exactly = 1) { usb.requestPermission(replacement, any<android.app.PendingIntent>()) }
            verify(exactly = 1) { usb.openDevice(replacement) }
        } finally { runtime.close() }
    }
    @Test fun cancellationDuringReplacementPermissionNeverAdmitsOrOpens() {
        val device = phone(false); val replacement = phone(true, "/dev/bus/usb/001/003"); val runtime = setup(device)
        freshPermission(replacement) { runtime.cancel() }
        request(device, 1) { detach(device); current[replacement.deviceName] = replacement }
        try {
            assertTrue(failure(runtime) is java.util.concurrent.CancellationException)
            assertTrue(accepted.isEmpty())
            verify(exactly = 0) { usb.openDevice(replacement) }
        } finally { runtime.close() }
    }
    @Test fun replacementChangedWhilePermissionPendingCannotBeAdmitted() {
        val device = phone(false); val replacement = phone(true, "/dev/bus/usb/001/003"); val newer = phone(true, "/dev/bus/usb/001/004")
        val runtime = setup(device)
        freshPermission(replacement) { current.clear(); current[newer.deviceName] = newer }
        request(device, 1) { detach(device); current[replacement.deviceName] = replacement }
        try {
            assertTrue(failure(runtime) is IphoneUsbException.DeviceUnavailable)
            assertTrue(accepted.isEmpty())
            verify(exactly = 1) { usb.openDevice(any()) }
        } finally { runtime.close() }
    }
    @Test fun missingConfigurationRequestsOnceAndAcceptsReadyReplacement() {
        val device = phone(false, includeNcm = false); val replacement = phone(true, "/dev/bus/usb/001/003"); val runtime = setup(device)
        request(device, 1) { detach(device); current[replacement.deviceName] = replacement }
        try {
            runToOpen(runtime)
            assertEquals(listOf(replacement), accepted)
            verify(exactly = 1) { connection.controlTransfer(any(), any(), any(), any(), any<ByteArray>(), any(), any()) }
        } finally { runtime.close() }
    }
    @Test fun acceptedReplacementStillMissingConfigurationIsRejectedWithoutAnotherRequest() {
        val device = phone(false); val replacement = phone(false, "/dev/bus/usb/001/003", includeNcm = false); val runtime = setup(device)
        request(device, 1) { detach(device); current[replacement.deviceName] = replacement }
        try {
            assertTrue(failure(runtime) is IllegalStateException)
            assertTrue(accepted.isEmpty())
            assertTrue(receipts.contains("CarPlay USB configuration found=false appleEthernet=false transitions=1 decision=reject-missing-configuration"))
            verify(exactly = 1) { connection.controlTransfer(any(), any(), any(), any(), any<ByteArray>(), any(), any()) }
            verify(exactly = 0) { usb.openDevice(replacement) }
        } finally { runtime.close() }
    }
    @Test fun readyReceiptsCrossActualRuntimeReporterAndManualUploaderWithoutTransition() {
        val device = phone(true); val runtime = setup(device)
        try {
            val contents = uploadWiredRuntimeReceipts(temp.newFolder()) { runToOpen(runtime) }
            assertTrue(contents, contents.contains("CarPlay USB configuration found=true appleEthernet=true transitions=0 decision=reuse-descriptors"))
            assertFalse(contents.contains("CarPlay USB transition "))
            assertEquals(1, contents.lineSequence().count { it.contains("CarPlay USB selection ") })
        } finally { runtime.close() }
    }
    @Test fun acceptedReplacementReceiptsCrossActualRuntimeReporterAndManualUploader() {
        val device = phone(false); val replacement = phone(false, "/dev/bus/usb/001/003"); val runtime = setup(device)
        request(device, 1) { detach(device); current[replacement.deviceName] = replacement }
        try {
            val contents = uploadWiredRuntimeReceipts(temp.newFolder()) { runToOpen(runtime) }
            assertTrue(contents, contents.contains("CarPlay USB transition attempt=1 result=accepted fallback=false"))
            assertTrue(contents, contents.contains("CarPlay USB configuration found=true appleEthernet=false transitions=1 decision=reuse-without-apple-ethernet"))
            assertEquals(2, contents.lineSequence().count { it.contains("CarPlay USB configuration ") })
            assertEquals(1, contents.lineSequence().count { it.contains("CarPlay USB selection ") })
        } finally { runtime.close() }
    }
    @Test fun rejectionDetachDuringOwnerAcknowledgementCannotFallThroughEvenWithSameNameReattach() {
        val device = phone(false); val runtime = setup(device); request(device)
        admission = { detach(device); current[device.deviceName] = device; true }
        try {
            assertTrue(failure(runtime) is IllegalStateException)
            verify(exactly = 1) { usb.openDevice(device) }
        } finally { runtime.close() }
    }
    @Test fun acceptedReplacementRemovedDuringOwnerAcknowledgementCannotBeOpened() {
        val device = phone(false); val replacement = phone(true, "/dev/bus/usb/001/003"); val runtime = setup(device)
        request(device, 1) { detach(device); current[replacement.deviceName] = replacement }
        admission = { current.remove(it.deviceName); true }
        try {
            failure(runtime)
            verify(exactly = 0) { usb.openDevice(replacement) }
        } finally { runtime.close() }
    }
    @Test fun ownerAcknowledgementRevocationCannotOpenFallback() {
        val device = phone(false); val runtime = setup(device); request(device)
        admission = { false }
        try {
            assertTrue(failure(runtime) is IllegalStateException)
            verify(exactly = 1) { usb.openDevice(device) }
        } finally { runtime.close() }
    }
    @Test fun rejectedFallbackReceiptsCrossActualRuntimeReporterAndManualUploader() {
        val device = phone(false); val runtime = setup(device); request(device)
        try {
            val contents = uploadWiredRuntimeReceipts(temp.newFolder()) { runToOpen(runtime) }
            assertTrue(contents, contents.contains("CarPlay USB configuration found=true appleEthernet=false transitions=0 decision=request-transition"))
            assertTrue(contents, contents.contains("CarPlay USB transition attempt=1 result=rejected fallback=true"))
            assertTrue(contents, contents.contains("CarPlay USB configuration found=true appleEthernet=false transitions=1 decision=reuse-without-apple-ethernet"))
            assertTrue(contents, contents.contains("CarPlay USB selection configurationId=5 usbmuxInterfaceId=1 usbmuxAlt=0 ncmControlInterfaceId=2 ncmControlAlt=0 ncmDataInterfaceId=3 ncmDataAlt=1"))
            assertEquals(2, contents.lineSequence().count { it.contains("CarPlay USB configuration ") })
            assertEquals(1, contents.lineSequence().count { it.contains("CarPlay USB transition ") })
            assertEquals(1, contents.lineSequence().count { it.contains("CarPlay USB selection ") })
        } finally { runtime.close() }
    }
    @Test fun finiteModeSchemasRejectUnallowlistedFieldsAndOutOfRangeDescriptorNumbers() {
        val valid = listOf(
            "CarPlay USB configuration found=true appleEthernet=false transitions=0 decision=request-transition",
            "CarPlay USB transition attempt=1 result=rejected fallback=true",
            "CarPlay USB selection configurationId=5 usbmuxInterfaceId=1 usbmuxAlt=0 ncmControlInterfaceId=2 ncmControlAlt=0 ncmDataInterfaceId=3 ncmDataAlt=1")
        valid.forEach { line ->
            assertNotNull(line, CarPlayAudioDiagnostics.sanitize(1, line))
            listOf(" serial=private", " address=private", " descriptor=private", "\n", " error=private").forEach {
                assertNull(CarPlayAudioDiagnostics.sanitize(1, line + it))
            }
        }
        assertNull(CarPlayAudioDiagnostics.sanitize(1, valid[0].replace("transitions=0", "transitions=2")))
        assertNull(CarPlayAudioDiagnostics.sanitize(1, valid[0].replace("request-transition", "private")))
        assertNull(CarPlayAudioDiagnostics.sanitize(1, valid[1].replace("attempt=1", "attempt=2")))
        assertNull(CarPlayAudioDiagnostics.sanitize(1, valid[2].replace("configurationId=5", "configurationId=256")))
        assertNull(CarPlayAudioDiagnostics.sanitize(1, valid[2].replace("ncmDataAlt=1", "ncmDataAlt=-1")))
    }
    @Test fun fallbackDescriptorsRequestOneModeTransitionBeforeUsbmux() {
        val device = phone(false)
        val runtime = setup(device)
        // Rejected vendor transfer: older peers retain their still-attached fallback.
        every { usb.openDevice(device) } returnsMany listOf(connection, null)
        every { connection.controlTransfer(any(), any(), any(), any(), any<ByteArray>(), any(), any()) } returns -1
        try {
            runToOpen(runtime)
            verify(exactly = 1) { connection.controlTransfer(0xc0, 0x52, 0, 4, any<ByteArray>(), 1, any()) }
            verify(exactly = 1) { connection.close() }
            verify(exactly = 2) { usb.openDevice(device) }
            assertEquals("Rejected request must disarm the exact manager transition deadline", listOf(device), accepted)
        } finally { runtime.close() }
    }
}
