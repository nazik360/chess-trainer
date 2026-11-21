package com.chesstrainer.vision

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Persists the per-piece appearance "fingerprints" captured once during
 * calibration (see BoardRecognizer.captureTemplates), so recognition works
 * again on the next app launch without recalibrating templates every time
 * (you only need to redo this step if the board's visual theme changes).
 */
object TemplateStore {

    private fun file(context: Context) = File(context.filesDir, "piece_templates.bin")

    fun save(context: Context, templates: Map<PieceClass, FloatArray>) {
        DataOutputStream(file(context).outputStream().buffered()).use { out ->
            out.writeInt(templates.size)
            for ((cls, vec) in templates) {
                out.writeUTF(cls.name)
                out.writeInt(vec.size)
                for (f in vec) out.writeFloat(f)
            }
        }
    }

    fun load(context: Context): Map<PieceClass, FloatArray>? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { input ->
                val count = input.readInt()
                val map = HashMap<PieceClass, FloatArray>()
                repeat(count) {
                    val name = input.readUTF()
                    val size = input.readInt()
                    val vec = FloatArray(size) { input.readFloat() }
                    map[PieceClass.valueOf(name)] = vec
                }
                map
            }
        } catch (e: Exception) {
            null
        }
    }

    fun exists(context: Context) = file(context).exists()
    fun clear(context: Context) { file(context).delete() }
}
