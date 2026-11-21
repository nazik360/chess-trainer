package com.chesstrainer.overlay

/** Whether CaptureService is currently alive, so MainActivity/CalibrationActivity
 * know whether they can reuse the existing projection + FrameHolder frames or
 * need to request a fresh MediaProjection grant first. */
object CaptureState {
    @Volatile var running: Boolean = false
}
