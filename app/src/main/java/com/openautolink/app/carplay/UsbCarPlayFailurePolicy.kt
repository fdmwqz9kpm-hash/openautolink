package com.openautolink.app.carplay

import com.shilapi.xcertplay.transport.*
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/** Memory-only exact enumeration identity; never exported or persisted. */
internal data class UsbCarPlayTopology(val name: String, val id: Int, val vendor: Int, val product: Int)

/** Shared by runtime instances, not manager instances. Absence revokes an exact fence; no timer retry. */
internal class UsbCarPlayRetryFence {
    private data class Failure(val reason: String, val permissionAwaitDenied: Boolean)
    private val failures = mutableMapOf<UsbCarPlayTopology, Failure>()
    @Synchronized fun blocked(target: UsbCarPlayTopology, attached: Set<UsbCarPlayTopology>,
        explicitPermissionRetry: Boolean = false): String? {
        failures.keys.retainAll(attached)
        val failure = failures[target] ?: return null
        // Admit only this attempt; retain the fence for every automatic rebuild/rescan.
        return failure.reason.takeUnless { explicitPermissionRetry && failure.permissionAwaitDenied }
    }
    @Synchronized fun failed(target: UsbCarPlayTopology, reason: String, permissionAwaitDenied: Boolean = false) {
        failures[target] = Failure(reason, permissionAwaitDenied && reason == "PERMISSION")
    }
}

internal object UsbCarPlayFailurePolicy {
    fun reason(failure: Exception, pending: Boolean = false): String = when (failure) {
        LockdownPairingException.PasswordProtected -> "PASSWORD_PROTECTED"
        LockdownPairingException.UserDeniedPairing -> "USER_DENIED"
        LockdownPairingException.Cancelled, is CancellationException, is InterruptedException -> "CANCELLED"
        LockdownPairingException.TimedOut -> if (pending) "TRUST_PENDING_TIMEOUT" else "TIMEOUT"
        is LockdownPairingException.RemoteError -> remoteReason(failure.code)
        is LockdownPairingException.InvalidResponse -> "INVALID_RESPONSE"
        is IphoneUsbException.DeviceUnavailable, is java.io.EOFException -> "TRANSPORT_LOSS"
        is SecurityException, is IphoneUsbException.PermissionDenied -> "PERMISSION"
        is TimeoutException, is IphoneUsbException.TimedOut -> "TIMEOUT"
        else -> "SETUP"
    }
    private fun remoteReason(code: String): String = when (code) {
        "PasswordProtected" -> "PASSWORD_PROTECTED"
        "UserDeniedPairing" -> "USER_DENIED"
        "InvalidPairRecord", "MissingPairRecord", "InvalidHostID" -> "INVALID_RECORD"
        else -> "REMOTE_ERROR"
    }
}
