// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** Constant space; at most 3s at 48k. No PCM history, only a split interleaved frame. */
internal class MicrophoneDiagnosticAccumulator(private val channelCount: Int) {
    init { require(channelCount in 1..2) }
    private class Level {
        var samples = 0L
        var nonzero = 0L
        var peak = 0.0
        var squares = 0.0
        fun add(value: Double) {
            samples++
            if (value != 0.0) nonzero++
            peak = maxOf(peak, abs(value))
            squares += value * value
        }
        fun snapshot() = MicrophoneDiagnosticLevel(samples, nonzero, ceil(peak).toInt(),
            if (samples == 0L) 0.0 else sqrt(squares / samples))
    }
    private val channels = List(channelCount) { Level() }
    private val sum = Level()
    private val difference = Level()
    private val mean = Level()
    private var pending: Int? = null
    private var frames = 0L
    private var nonzeroFrames = 0L

    fun add(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        for (i in 0 until count) {
            if (frames >= 144000L) break
            val value = samples[i].toInt()
            if (channelCount == 1) {
                channels[0].add(value.toDouble())
                frames++
                if (value != 0) nonzeroFrames++
            } else {
                val left = pending
                if (left == null) pending = value else {
                    pending = null
                    channels[0].add(left.toDouble()); channels[1].add(value.toDouble())
                    sum.add(left.toDouble() + value)
                    difference.add(left.toDouble() - value)
                    mean.add((left.toDouble() + value) / 2.0)
                    frames++
                    if (left != 0 || value != 0) nonzeroFrames++
                }
            }
        }
    }
    fun clearPartial() { pending = null }
    fun snapshot() = MicrophoneDiagnosticStatistics(frames, nonzeroFrames, channels.map { it.snapshot() },
        if (channelCount == 2) sum.snapshot() else null,
        if (channelCount == 2) difference.snapshot() else null,
        if (channelCount == 2) mean.snapshot() else null)
}
