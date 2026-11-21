package com.chesstrainer.engine

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the NNUE evaluation network Stockfish needs, once, on the phone's
 * own internet connection, and caches it in the app's private files dir.
 *
 * We deliberately do NOT bundle the ~140MB network inside the APK: it changes
 * with every Stockfish update, and shipping it would make the app huge for no
 * reason when the phone can just fetch it on first run.
 *
 * IMPORTANT: [DEFAULT_NET_NAME] must match `EvalFileDefaultName` in the
 * vendored source at app/src/main/cpp/stockfish/src/evaluate.h. If you pull a
 * newer Stockfish source in, update this constant to match.
 */
object NetworkManager {

    private const val DEFAULT_NET_NAME = "nn-bddd9d57eb87.nnue"

    // Mirrors in the same order Stockfish's own Makefile tries them.
    private val MIRRORS = listOf(
        "https://tests.stockfishchess.org/api/nn/$DEFAULT_NET_NAME",
        "https://github.com/official-stockfish/networks/raw/master/$DEFAULT_NET_NAME",
    )

    fun networkFile(context: Context): File =
        File(context.getDir("engine", Context.MODE_PRIVATE), DEFAULT_NET_NAME)

    fun isDownloaded(context: Context): Boolean {
        val f = networkFile(context)
        // A real NNUE file for this Stockfish version is well over 100MB;
        // anything much smaller means a previous download was interrupted.
        return f.exists() && f.length() > 50_000_000L
    }

    /**
     * Downloads the network, calling [onProgress] with 0..100 on a background
     * thread, and [onDone] with null on success or an error message on
     * failure. Both callbacks fire on whatever thread this was called from
     * dispatching the work on - call this off the main thread (see
     * MainActivity's download flow, which uses a plain background Thread).
     */
    fun download(context: Context, onProgress: (Int) -> Unit, onDone: (error: String?) -> Unit) {
        val dest = networkFile(context)
        val tmp = File(dest.parentFile, dest.name + ".part")

        var lastError: String? = null
        for (mirror in MIRRORS) {
            try {
                val url = URL(mirror)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.connect()

                if (conn.responseCode !in 200..299) {
                    lastError = "HTTP ${conn.responseCode} from $mirror"
                    conn.disconnect()
                    continue
                }

                val total = conn.contentLengthLong
                var downloaded = 0L
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                }
                conn.disconnect()

                if (tmp.length() < 50_000_000L) {
                    lastError = "Downloaded file from $mirror is too small (${tmp.length()} bytes) - mirror likely served an error page"
                    tmp.delete()
                    continue
                }

                tmp.renameTo(dest)
                onDone(null)
                return
            } catch (e: Exception) {
                lastError = "$mirror -> ${e.javaClass.simpleName}: ${e.message}"
                tmp.delete()
            }
        }
        onDone(lastError ?: "Unknown download failure")
    }
}
