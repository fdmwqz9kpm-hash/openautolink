// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

/** Internal platform boundary. Device references/tokens never enter public result DTOs. */
internal interface MicrophoneDiagnosticPlatform {
    fun inventory(): MicrophoneDiagnosticInventory
    fun policy(): MicrophoneDiagnosticReason
    fun open(shape: MicrophoneDiagnosticShape): MicrophoneDiagnosticRecord
}
internal interface MicrophoneDiagnosticRecord {
    fun preferNamedInput(): Boolean
    fun start()
    fun observe(): MicrophoneDiagnosticObservation
    fun read(buffer: ShortArray): Int
    fun stop()
    fun release()
}
internal data class MicrophoneDiagnosticObservation(
    val actual: MicrophoneDiagnosticShape,
    val client: MicrophoneDiagnosticShape?,
    val device: MicrophoneDiagnosticShape?,
    val silenced: Boolean?,
    val routeToken: Any?,
    val routeMatchesNamedInput: Boolean?
)
internal data class MicrophoneDiagnosticTiming(
    val captureMillis: Long = 2500,
    val armLimitMillis: Long = 3000,
    val wholeLimitMillis: Long = 15000,
    val graceMillis: Long = 500
)
