package com.pulsepointlabs.sarahmonitor.ble

/**
 * Pure retry rules for the strap link, kept apart from the Android Bluetooth code so they can be tested.
 */
internal object ReconnectPolicy {
    const val BASE_DELAY_MS = 2_500L
    const val MAX_DELAY_MS = 60_000L

    /**
     * Delay before the next attempt after [failures] consecutive failures: 2.5 s for the first, then
     * doubling (5 s, 10 s, 20 s, 40 s) up to a 60 s cap. Never gives up.
     */
    fun backoffDelayMs(failures: Int): Long {
        if (failures <= 1) return BASE_DELAY_MS
        val shift = (failures - 1).coerceAtMost(16)
        return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }

    /**
     * After a direct connect attempt has failed (strap out of range or asleep), let the Bluetooth stack wait
     * for it in the background instead: an autoConnect request has no timeout and reconnects as soon as the
     * strap advertises again, even while the phone's CPU sleeps.
     */
    fun useAutoConnect(consecutiveConnectFailures: Int): Boolean = consecutiveConnectFailures >= 1

    /** Blood-pressure cuff while armed: 5 s, 10 s, 20 s, 40 s, then once a minute between retries. */
    fun cuffRetryDelayMs(failures: Int): Long {
        if (failures <= 0) return CUFF_BASE_DELAY_MS
        return (CUFF_BASE_DELAY_MS shl failures.coerceAtMost(16)).coerceAtMost(MAX_DELAY_MS)
    }

    private const val CUFF_BASE_DELAY_MS = 5_000L

    /** Keep diagnostics readable on long outages: log the first few failures, then every tenth. */
    fun shouldLogFailure(failures: Int): Boolean = failures <= 3 || failures % 10 == 0
}
