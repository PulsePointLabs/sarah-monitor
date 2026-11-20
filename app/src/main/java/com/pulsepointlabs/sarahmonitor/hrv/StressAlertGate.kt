package com.pulsepointlabs.sarahmonitor.hrv

class StressAlertGate(
    private val sustainedDurationMs: Long = 5 * 60_000L,
    private val cooldownMs: Long = 45 * 60_000L,
    private val rearmScore: Int = 45
) {
    private var highStartedAt: Long? = null
    private var lastAlertAt = Long.MIN_VALUE
    private var armed = true

    fun update(nowMs: Long, score: Int?, threshold: Int): Boolean {
        if (score == null) {
            highStartedAt = null
            return false
        }
        if (score < rearmScore) {
            highStartedAt = null
            armed = true
            return false
        }
        if (score < threshold) {
            highStartedAt = null
            return false
        }
        val started = highStartedAt ?: nowMs.also { highStartedAt = it }
        val cooledDown = lastAlertAt == Long.MIN_VALUE || nowMs - lastAlertAt >= cooldownMs
        if (armed && cooledDown && nowMs - started >= sustainedDurationMs) {
            armed = false
            lastAlertAt = nowMs
            return true
        }
        return false
    }

    fun reset() {
        highStartedAt = null
        armed = true
    }
}
