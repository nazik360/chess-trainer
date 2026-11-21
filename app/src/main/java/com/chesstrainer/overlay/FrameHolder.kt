package com.chesstrainer.overlay

import android.graphics.Bitmap

/** Simple in-process holder for the most recent screenshot frame, shared
 * between CaptureService (producer) and CalibrationActivity / the analysis
 * loop (consumers). Everything here runs in one app process, so a plain
 * @Volatile field is enough - no need for IPC. */
object FrameHolder {
    @Volatile
    var latest: Bitmap? = null
}
