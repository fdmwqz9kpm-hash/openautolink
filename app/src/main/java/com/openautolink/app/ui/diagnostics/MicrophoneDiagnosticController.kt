package com.openautolink.app.ui.diagnostics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class MicrophoneDiagnosticController(
    val backend: MicrophoneDiagnosticBackend? = null,
    val automotive: Boolean = true,
    private val eligibility: (() -> Boolean)? = null,
    private val freshPark: (() -> Boolean)? = null,
) {
    var consent by androidx.compose.runtime.mutableStateOf(false)
        private set
    var stationary by androidx.compose.runtime.mutableStateOf(false)
        private set
    var state by androidx.compose.runtime.mutableStateOf(backend?.state ?: MicrophoneDiagnosticState())
        private set
    private var cancelPending = false
    @Volatile private var foreground = false
    @Volatile private var consentSnapshot = false
    @Volatile private var stationarySnapshot = false
    private var admission by androidx.compose.runtime.mutableStateOf(false)

    /** Safe for the backend worker: callbacks must only read current in-memory snapshots. */
    fun isEligibleNow(): Boolean = foreground && consentSnapshot &&
        runCatching {
            eligibility?.invoke() == true &&
                (if (automotive) freshPark?.invoke() == true else stationarySnapshot)
        }.getOrDefault(false)

    fun updateForeground(value: Boolean) {
        foreground = value
        if (!value) {
            consentSnapshot = false
            stationarySnapshot = false
            consent = false
            stationary = false
        }
        refresh()
    }
    val busy: Boolean get() = state.phase in setOf(MicrophoneDiagnosticPhase.RUNNING,
        MicrophoneDiagnosticPhase.INSPECTING, MicrophoneDiagnosticPhase.STOPPING)
    val canStart: Boolean get() = admission && !busy && backend != null && isEligibleNow()

    fun refresh() {
        admission = isEligibleNow()
        val next = backend?.state ?: return
        if (next.phase == MicrophoneDiagnosticPhase.COMPLETE || next.phase == MicrophoneDiagnosticPhase.IDLE) cancelPending = false
        state = if (cancelPending) next.copy(phase = MicrophoneDiagnosticPhase.STOPPING, result = null) else next
        if (!admission) cancel()
    }
    fun updateConsent(value: Boolean) { consentSnapshot = value; consent = value; refresh() }
    fun updateStationary(value: Boolean) { stationarySnapshot = value; stationary = value; refresh() }
    fun start(comparison: MicrophoneDiagnosticComparison) = submit { it.start(comparison) }
    fun inspect() = submit { it.inspect() }
    private fun submit(action: (MicrophoneDiagnosticBackend) -> Boolean) {
        refresh()
        if (!canStart) return
        val accepted = action(backend ?: return)
        refresh()
        if (!accepted) state = state.copy(reason = MicrophoneDiagnosticReason.BUSY)
    }
    fun cancel() {
        if (!busy || cancelPending || state.phase == MicrophoneDiagnosticPhase.STOPPING) return
        cancelPending = true
        state = state.copy(phase = MicrophoneDiagnosticPhase.STOPPING, result = null)
        backend?.cancel()
    }
}
