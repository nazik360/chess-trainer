package com.chesstrainer.overlay

import android.graphics.PointF

/** Latest analysis result, pushed by CaptureService's background analysis
 * loop and consumed by OverlayService to draw the suggestion. */
object AnalysisBus {

    data class Suggestion(
        val fromSquare: String,
        val toSquare: String,
        val fromPoint: PointF,
        val toPoint: PointF,
        val scoreCp: Int?,
        val scoreMate: Int?,
        val depth: Int,
    )

    sealed class State {
        object Idle : State()
        object Thinking : State()
        data class Ready(val suggestion: Suggestion) : State()
        data class Error(val message: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set

    private val listeners = mutableListOf<(State) -> Unit>()

    fun subscribe(listener: (State) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
        listener(state)
    }

    fun unsubscribe(listener: (State) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun publish(newState: State) {
        state = newState
        synchronized(listeners) { listeners.toList() }.forEach { it(newState) }
    }
}
