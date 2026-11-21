package com.chesstrainer.vision

import android.content.Context
import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject

/**
 * The four screen corners of the board (in pixels, in the orientation the
 * screen was in when you calibrated) plus whether the board is shown flipped
 * (black at the bottom - common when you're playing/reviewing as black).
 *
 * Corners are stored a8 -> h8 -> h1 -> a1 (top-left, top-right, bottom-right,
 * bottom-left as drawn on screen) *before* accounting for [flipped]; see
 * BoardRecognizer for how that's used.
 */
data class Calibration(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF,
    val flipped: Boolean,
    val screenWidth: Int,
    val screenHeight: Int,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("tl", pointJson(topLeft))
        o.put("tr", pointJson(topRight))
        o.put("br", pointJson(bottomRight))
        o.put("bl", pointJson(bottomLeft))
        o.put("flipped", flipped)
        o.put("w", screenWidth)
        o.put("h", screenHeight)
        return o.toString()
    }

    private fun pointJson(p: PointF) = JSONArray().put(p.x.toDouble()).put(p.y.toDouble())

    companion object {
        private const val PREFS = "chesstrainer_calibration"
        private const val KEY = "calibration_json"

        fun load(context: Context): Calibration? {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return null
            return try {
                val o = JSONObject(raw)
                fun pt(name: String): PointF {
                    val a = o.getJSONArray(name)
                    return PointF(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
                }
                Calibration(
                    pt("tl"), pt("tr"), pt("br"), pt("bl"),
                    o.getBoolean("flipped"), o.getInt("w"), o.getInt("h"),
                )
            } catch (e: Exception) {
                null
            }
        }

        fun save(context: Context, calibration: Calibration) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, calibration.toJson())
                .apply()
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
