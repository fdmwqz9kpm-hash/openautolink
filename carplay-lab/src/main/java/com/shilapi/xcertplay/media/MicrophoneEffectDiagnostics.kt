// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.media.AudioRecordingConfiguration
import android.media.audiofx.AudioEffect

/** Android's public getClientEffects/getEffects lists describe *enabled* effects, not inventory.
 * Inspect at most 16 descriptor types from the exact recorder's configuration. Never instantiate
 * effects or read their UUID/name/implementor, and never equate this with native GM ECNR evidence.
 * Missing from a complete list means false; missing from a truncated/partial list stays unknown.
 */
internal object MicrophoneEffectDiagnostics {
    private const val MAX_EFFECTS = 16

    fun receipts(config: Result<AudioRecordingConfiguration?>, source: MicrophoneSource, rate: Int): List<String> =
        listOf("client", "capture").map { chain ->
            val state = if (config.isFailure) "unavailable" else if (config.getOrNull() == null) "absent" else null
            val effects = if (state == null) runCatching {
                if (chain == "client") config.getOrThrow()!!.clientEffects else config.getOrThrow()!!.effects
            }.getOrNull() else null
            val evidence = if (effects == null) "result=${state ?: "unavailable"} examined=0 truncated=false aec=unknown ns=unknown agc=unknown other=0"
                else summarize(effects)
            "media stage=microphone-effects scope=own source=$source rate=$rate chain=$chain $evidence"
        }

    private fun summarize(effects: List<AudioEffect.Descriptor>): String {
        // OEM failures remain local to this chain, not the sibling list or configuration receipt.
        return runCatching {
            val examined = effects.size.coerceIn(0, MAX_EFFECTS)
            val truncated = effects.size > MAX_EFFECTS
            var incomplete = false
            var aec = false; var ns = false; var agc = false; var other = 0
            repeat(examined) { index ->
                val type = runCatching { effects[index].type }.getOrNull()
                when (type) {
                    null -> incomplete = true
                    AudioEffect.EFFECT_TYPE_AEC -> aec = true
                    AudioEffect.EFFECT_TYPE_NS -> ns = true
                    AudioEffect.EFFECT_TYPE_AGC -> agc = true
                    else -> other++
                }
            }
            fun enabled(found: Boolean) = if (found) "true" else if (truncated || incomplete) "unknown" else "false"
            val result = if (truncated) "truncated" else if (incomplete) "partial" else "ok"
            "result=$result examined=$examined truncated=$truncated aec=${enabled(aec)} ns=${enabled(ns)} agc=${enabled(agc)} other=$other"
        }.getOrDefault("result=unavailable examined=0 truncated=false aec=unknown ns=unknown agc=unknown other=0")
    }
}
