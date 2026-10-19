package com.openautolink.app.ui.projection

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring supplements the real manager/permission/visibility behavior regression. */
class UsbAuthorizationRecoveryWiringTest {
    private val screen get() = File("src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt").readText()

    @Test fun recoveryGateObservesLiveUsbCandidatesBeforeCarPlayEarlyReturn() {
        val beforeCarPlay = screen.substringBefore("if (carPlayState.selected) {")
        assertTrue(beforeCarPlay.contains("val transportMode by viewModel.transportMode.collectAsStateWithLifecycle()"))
        assertTrue(beforeCarPlay.contains(".connectionState.collectAsStateWithLifecycle()"))
        assertTrue(beforeCarPlay.contains(".availableDevices.collectAsStateWithLifecycle()"))
        assertTrue(beforeCarPlay.contains("UsbPickerVisibility.shouldShow("))
        assertTrue(beforeCarPlay.contains("carPlayEnabled = carPlayEnabled"))
        assertTrue(beforeCarPlay.contains("usbState = usbState"))
        assertTrue(beforeCarPlay.contains("devices = usbDevices"))
        assertTrue(beforeCarPlay.contains("usbHotPlugPrompt == null"))
        assertTrue(beforeCarPlay.contains("!showCarPlaySafeEditor"))
    }

    @Test fun bothProjectionBranchesRenderRecoveryWithoutFailureDialogOcclusion() {
        val selected = screen.substringAfter("if (carPlayState.selected) {").substringBefore("\n        return")
        val aa = screen.substringAfter("\n        return\n    }")
        assertTrue("CarPlay's early return must not bypass recovery", selected.contains("if (showUsbPicker)"))
        assertTrue(selected.contains("UsbDevicePickerOverlay(onOpenSettings = { showSettings = true })"))
        val shared = screen.substringBefore("if (carPlayState.selected) {")
        assertTrue("Failures survive automatic restoration to the AA branch",
            shared.contains("carPlayState.failure?.let"))
        assertTrue("Picker must not mask actionable failure guidance",
            shared.contains("val showUsbPicker = carPlayState.failure == null"))
        assertTrue("Acknowledged fenced attachments must not reopen the picker",
            shared.contains("!viewModel.isUsbCarPlayRecoveryBlocked(it)"))
        assertTrue(aa.contains("if (showUsbPicker)"))
        assertTrue(aa.contains("UsbDevicePickerOverlay(onOpenSettings = { showSettings = true })"))
    }

    @Test fun onlyExactRowTapSelectsUsbAndExistingSwitchStayRemainViewModelOwned() {
        val picker = screen.substringAfter("private fun UsbDevicePickerOverlay")
        val click = picker.substringAfter(".clickable {").substringBefore(".padding(")
        assertTrue("Picker must pass its exact enumeration receipt, not only a reusable bus path",
            click.contains(".selectDevice(device)"))
        assertEquals(1, Regex("\\.selectDevice\\(").findAll(screen).count())
        val beforePicker = screen.substringBefore("private fun UsbDevicePickerOverlay")
        assertFalse(beforePicker.contains("UsbAttachAuthorization.explicitRetry"))
        assertFalse(beforePicker.contains("requestPermission("))
        assertFalse(beforePicker.contains("setDirectTransport("))
        assertTrue(beforePicker.contains("viewModel.acceptUsbHotPlug(prompt.generation)"))
        assertTrue(beforePicker.contains("viewModel.stayOnCurrentProjection(prompt.generation)"))
    }
}
