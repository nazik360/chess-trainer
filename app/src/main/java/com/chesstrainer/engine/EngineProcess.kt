package com.chesstrainer.engine

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the vendored Stockfish binary as a subprocess and speaks UCI to it
 * over stdin/stdout. We never link against it as a native library (no JNI,
 * no dlopen) - it's genuinely just an executable, named libstockfish-engine.so
 * only so Android's packager extracts it with the executable bit set (see the
 * comment in app/src/main/cpp/CMakeLists.txt for why that's necessary).
 */
class EngineProcess(private val context: Context) {

    data class AnalysisResult(
        val bestMoveUci: String,
        val ponderUci: String?,
        val scoreCp: Int?,
        val scoreMate: Int?,
        val depth: Int,
        val pvUci: List<String>,
    )

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val requestId = AtomicLong(0)

    @Volatile private var running = false

    /** Set by [start] when it returns false, so callers can surface a real reason
     * instead of a generic "failed to start" message. */
    var lastError: String? = null
        private set

    /** Starts the subprocess and performs the UCI handshake. Blocking - call off the main thread.
     * On failure, check [lastError] for why. */
    fun start(): Boolean {
        lastError = null
        val binary = File(context.applicationInfo.nativeLibraryDir, "libstockfish-engine.so")
        if (!binary.exists()) {
            lastError = "Engine binary missing at ${binary.absolutePath} - nativeLibraryDir contents: " +
                (binary.parentFile?.list()?.joinToString() ?: "parentFile is null/unreadable")
            Log.e(TAG, lastError!!)
            return false
        }
        if (!binary.canExecute()) {
            // Legacy packaging should extract it with the exec bit already set,
            // but some OEM ROMs/installers have been seen to drop it - try to
            // fix it up ourselves before giving up.
            binary.setExecutable(true, false)
        }
        if (!binary.canExecute()) {
            lastError = "Engine binary at ${binary.absolutePath} is not executable " +
                "(size=${binary.length()} bytes) - the OS wouldn't let us set the exec bit either"
            Log.e(TAG, lastError!!)
            return false
        }

        return try {
            val pb = ProcessBuilder(binary.absolutePath)
            pb.redirectErrorStream(true)
            pb.environment()["STOCKFISH_HOME"] = context.getDir("engine", Context.MODE_PRIVATE).absolutePath
            process = pb.start()
            writer = OutputStreamWriter(process!!.outputStream)
            running = true

            // Give a crashing/incompatible binary (e.g. ABI/ISA mismatch -
            // "Exec format error", or a missing dynamic dependency) a brief
            // moment to die before we start waiting on handshake tokens that
            // will otherwise just time out silently.
            Thread.sleep(200)
            if (process?.isAlive == false) {
                val exit = process?.exitValue()
                val output = try { process?.inputStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                lastError = "Engine process exited immediately (exit code $exit)." +
                    (if (!output.isNullOrBlank()) " Output: $output" else " No output captured - likely incompatible CPU architecture for this build (check: device ABI vs app/build.gradle.kts ndk.abiFilters).")
                Log.e(TAG, lastError!!)
                return false
            }

            send("uci")
            if (!waitFor("uciok", timeoutMs = 10_000)) {
                lastError = "Engine did not respond with 'uciok' within 10s " +
                    "(process alive=${process?.isAlive}) - binary may be incompatible with this device's CPU"
                Log.e(TAG, lastError!!)
                return false
            }

            val netFile = NetworkManager.networkFile(context)
            send("setoption name EvalFile value ${netFile.absolutePath}")
            send("setoption name Threads value ${recommendedThreads()}")
            send("setoption name Hash value 128")

            send("isready")
            if (!waitFor("readyok", timeoutMs = 10_000)) {
                lastError = "Engine did not respond with 'readyok' within 10s " +
                    "(process alive=${process?.isAlive}) - check the NNUE network file is valid: ${netFile.absolutePath} (exists=${netFile.exists()}, size=${netFile.length()})"
                Log.e(TAG, lastError!!)
                return false
            }
            true
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Failed to start engine", e)
            false
        }
    }

    private fun recommendedThreads(): Int =
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)

    /**
     * Sets the position from a FEN string (as produced by BoardRecognizer /
     * FenBuilder) and searches for [moveTimeMs] milliseconds, returning the
     * best move found. Blocking - call off the main thread.
     */
    fun analyzeFen(fen: String, moveTimeMs: Int = 1200): AnalysisResult? {
        if (!running) return null
        send("position fen $fen")
        send("go movetime $moveTimeMs")

        var depth = 0
        var scoreCp: Int? = null
        var scoreMate: Int? = null
        var pv: List<String> = emptyList()
        var bestMove: String? = null
        var ponder: String? = null

        val deadline = System.currentTimeMillis() + moveTimeMs + 5_000
        val reader = process?.inputStream?.bufferedReader() ?: return null

        // We attach one persistent reader thread in readLoop(); here we just
        // poll the latest parsed state via the shared fields below. To keep
        // this class simple and dependency-free we instead do a direct
        // synchronous read loop scoped to this call.
        while (System.currentTimeMillis() < deadline) {
            val line = pendingLines.poll() ?: run { Thread.sleep(5); null } ?: continue
            if (line.startsWith("info ")) {
                parseInfoLine(line)?.let { info ->
                    depth = info.first
                    scoreCp = info.second
                    scoreMate = info.third
                    if (info.fourth.isNotEmpty()) pv = info.fourth
                }
            } else if (line.startsWith("bestmove")) {
                val parts = line.trim().split(" ")
                bestMove = parts.getOrNull(1)
                val ponderIdx = parts.indexOf("ponder")
                ponder = if (ponderIdx >= 0) parts.getOrNull(ponderIdx + 1) else null
                break
            }
        }

        val bm = bestMove ?: return null
        return AnalysisResult(bm, ponder, scoreCp, scoreMate, depth, pv)
    }

    // depth, scoreCp, scoreMate, pv
    private data class InfoTuple(val first: Int, val second: Int?, val third: Int?, val fourth: List<String>)
    private fun parseInfoLine(line: String): InfoTuple? {
        val tokens = line.trim().split(" ")
        var depth = 0
        var cp: Int? = null
        var mate: Int? = null
        var pv: List<String> = emptyList()
        var i = 0
        while (i < tokens.size) {
            when (tokens[i]) {
                "depth" -> { depth = tokens.getOrNull(i + 1)?.toIntOrNull() ?: depth; i += 2 }
                "score" -> {
                    when (tokens.getOrNull(i + 1)) {
                        "cp" -> { cp = tokens.getOrNull(i + 2)?.toIntOrNull(); i += 3 }
                        "mate" -> { mate = tokens.getOrNull(i + 2)?.toIntOrNull(); i += 3 }
                        else -> i += 1
                    }
                }
                "pv" -> { pv = tokens.subList(i + 1, tokens.size); i = tokens.size }
                else -> i += 1
            }
        }
        return InfoTuple(depth, cp, mate, pv)
    }

    private val pendingLines = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var readerThread: Thread? = null

    private fun ensureReaderThread() {
        if (readerThread != null) return
        val stream = process?.inputStream ?: return
        readerThread = Thread {
            val br = BufferedReader(InputStreamReader(stream))
            try {
                while (true) {
                    val line = br.readLine() ?: break
                    pendingLines.add(line)
                    synchronized(waiters) {
                        waiters.forEach { it(line) }
                    }
                }
            } catch (_: Exception) {
                // process died / stream closed
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private val waiters = mutableListOf<(String) -> Unit>()

    private fun send(command: String) {
        ensureReaderThread()
        writer?.apply {
            write(command)
            write("\n")
            flush()
        }
    }

    /** Returns true if [token] was seen within [timeoutMs], false on timeout. */
    private fun waitFor(token: String, timeoutMs: Long): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        val waiter: (String) -> Unit = { line -> if (line.contains(token)) latch.countDown() }
        synchronized(waiters) { waiters.add(waiter) }
        val got = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        synchronized(waiters) { waiters.remove(waiter) }
        return got
    }

    fun stop() {
        running = false
        try {
            send("quit")
        } catch (_: Exception) { }
        process?.destroy()
        process = null
    }

    companion object {
        private const val TAG = "EngineProcess"
    }
}
