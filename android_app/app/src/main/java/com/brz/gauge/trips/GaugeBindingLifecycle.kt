package com.brz.gauge.trips

/** CDM can report one selection through both the callback and activity result. */
internal class BindingCompletion {
    private var handled = false
    fun begin() { handled = false }
    fun claim(): Boolean {
        if (handled) return false
        handled = true
        return true
    }
}

/** A monotonic, bounded hold: background wakeups cannot interrupt LE bonding. */
internal class BondConnectionPause {
    private var untilMs = 0L
    fun begin(nowMs: Long) { untilMs = nowMs + DURATION_MS }
    fun resume() { untilMs = 0L }
    fun isPaused(nowMs: Long): Boolean = nowMs < untilMs
    companion object { const val DURATION_MS = 60_000L }
}
