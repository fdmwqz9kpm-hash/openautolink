package com.openautolink.app.cluster

/** Pure state machine for ownership of the host's single active navigation slot. */
internal class ClusterNavigationLifecyclePolicy {

    enum class Action {
        NONE,
        START_AND_UPDATE,
        UPDATE,
        END,
    }

    private var state = State.IDLE

    fun onRouteAvailable(): Action = when (state) {
        State.IDLE -> {
            state = State.NAVIGATING
            Action.START_AND_UPDATE
        }
        State.NAVIGATING -> Action.UPDATE
        State.SUPPRESSED_UNTIL_CLEAR -> Action.NONE
    }

    fun onRouteCleared(): Action = when (state) {
        State.IDLE -> Action.NONE
        State.NAVIGATING -> {
            state = State.IDLE
            Action.END
        }
        State.SUPPRESSED_UNTIL_CLEAR -> {
            state = State.IDLE
            Action.NONE
        }
    }

    /** Withdraw the displayed Trip, not the source route or its suppression state. */
    fun onGuidanceUnavailable(): Action = when (state) {
        State.NAVIGATING -> {
            state = State.IDLE
            Action.END
        }
        State.IDLE, State.SUPPRESSED_UNTIL_CLEAR -> Action.NONE
    }

    fun onHostStop() {
        // A host stop can arrive while a live source route has no usable instruction.
        state = State.SUPPRESSED_UNTIL_CLEAR
    }

    fun onNavigationStartFailed() {
        if (state == State.NAVIGATING) state = State.IDLE
    }

    fun onRouteTerminated(): Action = when (state) {
        State.NAVIGATING -> {
            state = State.SUPPRESSED_UNTIL_CLEAR
            Action.END
        }
        State.IDLE, State.SUPPRESSED_UNTIL_CLEAR -> Action.NONE
    }

    private enum class State {
        IDLE,
        NAVIGATING,
        SUPPRESSED_UNTIL_CLEAR,
    }
}
