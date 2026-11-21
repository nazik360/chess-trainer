package com.chesstrainer.overlay

/** Lightweight in-memory session state, toggled from the overlay's control
 * chip while a session is running. Not persisted - each new session starts
 * paused with white to move, which is the common case. */
object SessionSettings {
    @Volatile var whiteToMove: Boolean = true
    @Volatile var paused: Boolean = false
    @Volatile var moveTimeMs: Int = 1200
    @Volatile var captureIntervalMs: Long = 1500
}
