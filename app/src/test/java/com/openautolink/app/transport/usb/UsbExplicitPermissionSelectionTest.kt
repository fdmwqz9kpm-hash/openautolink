package com.openautolink.app.transport.usb

import android.content.Context
import android.hardware.usb.*
import io.mockk.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test


@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsbExplicitPermissionSelectionTest {
    private val usb = mockk<UsbManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.USB_SERVICE) } returns usb
    }
    private val scope = TestScope()
    private val mux = mockk<UsbInterface>(relaxed = true) {
        every { interfaceClass } returns 255; every { interfaceSubclass } returns 254; every { interfaceProtocol } returns 2
    }
    private fun phone(id: Int) = mockk<UsbDevice>(relaxed = true) {
        every { deviceName } returns "/dev/bus/usb/011/002"; every { deviceId } returns id
        every { vendorId } returns 0x05ac; every { productId } returns 0x12a8
        every { interfaceCount } returns 1; every { getInterface(0) } returns mux
    }
    private val apple = phone(2)
    private val census = java.util.concurrent.ConcurrentHashMap<String, UsbDevice>().apply { put(apple.deviceName, apple) }
    private val offers = mutableListOf<Long>()
    private val manager = UsbConnectionManager(context, scope, {}, onCandidate = { generation, _ -> offers.add(generation) },
        onIphoneSelected = { true })
    private fun start() {
        every { usb.deviceList } answers { HashMap(census) }
        manager.start()
        assertEquals(1, offers.size)
    }
    @After fun cleanup() {
        manager.stop()
        val jobs = scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList()
        scope.cancel()
        scope.runCurrent()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(5_000) { jobs.forEach { it.join() } }
        }
        assertTrue("Actual worker jobs must retire before removing mocks", jobs.all { it.isCompleted })
        unmockkAll()
    }

    @Test fun staleRenderedPickerRowCannotAuthorizeSameNameReplacement() {
        start()
        val displayed = UsbConnectionManager.availableDevices.value.single()
        census[apple.deviceName] = phone(3)
        UsbConnectionManager.selectDevice(displayed)
        assertEquals("A stale rendered row must not mint new consent for a replacement enumeration", 1, offers.size)
    }

    private fun select() = UsbConnectionManager.selectDevice(UsbConnectionManager.availableDevices.value.single())
    private fun admitPicker(): Pair<() -> Boolean, () -> Boolean> {
        select()
        assertTrue(manager.accept(offers.last()))
        return manager.iphonePermissionCallbacks(apple)
    }
    private fun detach() {
        manager.javaClass.getDeclaredMethod("handleDeviceDetached", UsbDevice::class.java)
            .apply { isAccessible = true }.invoke(manager, apple)
    }

    @Test fun automaticAdmissionNeverMintsExplicitPermissionRetry() {
        start()
        assertTrue(manager.accept(offers.last()))
        val receipt = manager.iphonePermissionCallbacks(apple)
        assertTrue(receipt.first())
        assertFalse(receipt.second())
        assertFalse(receipt.second())
        verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<android.app.PendingIntent>()) }
    }

    @Test fun explicitPickerReceiptIsConsumedOnceAcrossRepeatedRuntimeCaptures() {
        start()
        val receipt = admitPicker()
        assertTrue(receipt.first())
        assertTrue(receipt.second())
        assertFalse(receipt.second())
        assertFalse(manager.iphonePermissionCallbacks(apple).second())
        assertTrue(receipt.first()) // Consumption does not retire real permission ownership.
    }

    @Test fun explicitSwitchConfirmsOnlyItsExactCurrentCandidate() {
        start()
        assertTrue(manager.accept(offers.last(), explicitUserSelection = true))
        assertTrue(manager.iphonePermissionCallbacks(apple).second())
        assertFalse(manager.iphonePermissionCallbacks(apple).second())
    }

    @Test fun wrongEnumerationCaptureCannotConsumeTheRealOwnersIntent() {
        start()
        val receipt = admitPicker()
        val wrong = manager.iphonePermissionCallbacks(phone(3))
        assertFalse(wrong.first())
        assertFalse(wrong.second())
        assertTrue("A foreign enumeration callback cannot steal the valid exact owner's one-shot intent", receipt.second())
    }

    @Test fun oldOwnerCannotConsumeOrReleaseSuccessorIntent() {
        start()
        val old = admitPicker()
        val retired = manager.iphoneRetirementAcknowledgement(apple)
        retired()
        val fresh = admitPicker()
        assertFalse(old.first())
        assertFalse(old.second())
        retired()
        assertTrue(fresh.first())
        assertTrue(fresh.second())
    }

    @Test fun deliveredDetachCannotBeUndoneBySameNameSameIdCensusReappearance() {
        start()
        val receipt = admitPicker()
        detach()
        census[apple.deviceName] = apple
        assertFalse(receipt.first())
        assertFalse(receipt.second())
    }

    @Test fun stopStartCannotReviveOldSelectionOrConsumeFreshSelection() {
        start()
        val old = admitPicker()
        manager.stop()
        manager.start()
        val fresh = admitPicker()
        assertFalse(old.first())
        assertFalse(old.second())
        assertTrue(fresh.first())
        assertTrue(fresh.second())
    }

    @Test fun changedCandidateCannotBeAcceptedEvenByExplicitSwitch() {
        start()
        val oldGeneration = offers.last()
        census[apple.deviceName] = phone(3)
        assertNull(manager.pendingDevice(oldGeneration))
        assertFalse(manager.accept(oldGeneration, explicitUserSelection = true))
        assertFalse(manager.iphonePermissionCallbacks(apple).first())
    }

    @Test fun stayAndFreshGenerationCannotReuseOldCandidate() {
        start()
        val oldGeneration = offers.last()
        manager.stay(oldGeneration)
        assertFalse(manager.accept(oldGeneration, explicitUserSelection = true))
        select()
        assertNotEquals(oldGeneration, offers.last())
        assertFalse(manager.accept(oldGeneration, explicitUserSelection = true))
        assertTrue(manager.accept(offers.last()))
        assertTrue(manager.iphonePermissionCallbacks(apple).second())
    }

    @Test fun genericRescanDiscardsPendingPickerIntentInsteadOfReauthorizingIt() {
        start()
        select()
        val oldGeneration = offers.last()
        manager.retryCandidates()
        assertNotEquals(oldGeneration, offers.last())
        assertFalse(manager.accept(oldGeneration, explicitUserSelection = true))
        assertTrue(manager.accept(offers.last()))
        assertFalse(manager.iphonePermissionCallbacks(apple).second())
    }

    @Test fun changedCensusAfterDispatchRevokesCapturedOwnerAndIntent() {
        start()
        val receipt = admitPicker()
        census[apple.deviceName] = phone(3)
        assertFalse(receipt.first())
        assertFalse(receipt.second())
    }
}
