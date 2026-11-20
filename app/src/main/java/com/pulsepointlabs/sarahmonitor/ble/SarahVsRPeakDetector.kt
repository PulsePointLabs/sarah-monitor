package com.pulsepointlabs.sarahmonitor.ble

import kotlin.math.abs

/** Streaming detector used only to publish low-latency beat events to companion apps. */
internal class SarahVsRPeakDetector(private val sampleRate: Int = 130) {
    private var baseline = 0.0
    private var envelope = 100.0
    private var lastPeakSample = -sampleRate
    private var sampleIndex = 0

    fun add(voltageUv: Int): Boolean {
        baseline += (voltageUv - baseline) * 0.015
        val magnitude = abs(voltageUv - baseline)
        envelope += (magnitude - envelope) * 0.01
        val refractorySamples = (sampleRate * 0.25).toInt()
        val threshold = (envelope * 3.2).coerceAtLeast(180.0)
        val detected = magnitude > threshold && sampleIndex - lastPeakSample >= refractorySamples
        if (detected) lastPeakSample = sampleIndex
        sampleIndex++
        return detected
    }

    fun reset() {
        baseline = 0.0
        envelope = 100.0
        lastPeakSample = -sampleRate
        sampleIndex = 0
    }
}
