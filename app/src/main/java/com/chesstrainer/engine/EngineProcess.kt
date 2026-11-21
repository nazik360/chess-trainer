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
            // Keep a few alternative candidate moves available (see
            // analyzeFenCandidates) - if our own recognition isn't confident
            // the top move is legal on the real board, we can fall back to
            // the engine's 2nd/3rd choice instead of just refusing to show
            // anything.
            send("setoption name MultiPV value $MULTI_PV")
            // Kept modest on purpose: the NNUE network itself already uses a
            // meaningful chunk of memory, and a phone under memory pressure
            // will have Android's low-memory killer terminate this background
            // subprocess outright (which surfaces as a confusing "Stream
            // closed" the next time we try to talk to it) rather than fail
            // gracefully. A short per-move think time doesn't benefit much
            // from a bigger hash table anyway.
            send("setoption name Hash value 64")

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
    fun analyzeFen(fen: String, moveTimeMs: Int = 1200): AnalysisResult? =
        analyzeFenCandidates(fen, moveTimeMs).firstOrNull()

    /**
     * Same search as [analyzeFen], but returns up to [MULTI_PV] candidate
     * moves (the engine's own ranked alternatives, via the UCI "MultiPV"
     * option set in [start]) instead of only the single best one, ordered
     * best-first.
     *
     * Why this exists: [com.chesstrainer.vision.BoardSanity.checkMoveConsistency]
     * can reject the top move as inconsistent with our own recognized board
     * (an occupied square we're not confident about, say). Previously that
     * meant showing nothing at all for that frame. Now the caller can fall
     * back to the engine's 2nd or 3rd choice instead - still a real
     * Stockfish-ranked move for this exact position, just not its favorite -
     * and only give up if every candidate fails the check.
     */
    fun analyzeFenCandidates(fen: String, moveTimeMs: Int = 1200): List<AnalysisResult> {
        if (!running) return emptyList()
        send("position fen $fen")
        send("go movetime $moveTimeMs")

        // multipv index -> latest (depth, scoreCp, scoreMate, pv) for that line.
        val pvState = HashMap<Int, InfoTuple>()
        var sawBestMove = false

        val deadline = System.currentTimeMillis() + moveTimeMs + 5_000

        while (System.currentTimeMillis() < deadline) {
            val line = pendingLines.poll() ?: run { Thread.sleep(5); null } ?: continue
            if (line.startsWith("info ") && line.contains(" pv ")) {
                val tokens = line.trim().split(" ")
                val multiPvIdx = tokens.indexOf("multipv").let { i -> if (i >= 0) tokens.getOrNull(i + 1)?.toIntOrNull() ?: 1 else 1 }
                parseInfoLine(line)?.let { info ->
                    if (info.fourth.isNotEmpty()) pvState[multiPvIdx] = info
                }
            } else if (line.startsWith("bestmove")) {
                sawBestMove = true
                break
            }
        }
        if (!sawBestMove && pvState.isEmpty()) return emptyList()

        return pvState.entries.sortedBy { it.key }.mapNotNull { (_, info) ->
            val mv = info.fourth.firstOrNull() ?: return@mapNotNull null
            AnalysisResult(mv, null, info.second, info.third, info.first, info.fourth)
        }
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
    // Rolling tail of everything the engine has printed (stdout+stderr, merged -
    // see redirectErrorStream(true) in start()), so if it dies mid-session we can
    // report *why* instead of just the generic "Stream closed" Java throws when
    // you write to a dead process's stdin pipe.
    private val recentOutput = java.util.Collections.synchronizedList(mutableListOf<String>())
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
                    synchronized(recentOutput) {
                        recentOutput.add(line)
                        if (recentOutput.size > 50) recentOutput.removeAt(0)
                    }
                    synchronized(waiters) {
                        waiters.forEach { it(line) }
                    }
                }
            } catch (_: Exception) {
                // process died / stream closed - nothing more to read
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private val waiters = mutableListOf<(String) -> Unit>()

    /** True once start() has returned true and the process hasn't since died. */
    val isRunning: Boolean get() = running && process?.isAlive == true

    private fun send(command: String) {
        ensureReaderThread()
        try {
            writer?.apply {
                write(command)
                write("\n")
                flush()
            }
        } catch (e: Exception) {
            running = false
            val exit = try { process?.exitValue() } catch (_: Exception) { null }
            val tail = synchronized(recentOutput) { recentOutput.joinToString("\n") }
            throw IllegalStateException(
                "Engine process died while sending '$command' (exit code $exit). " +
                    if (tail.isNotBlank()) "Last engine output:\n$tail" else "No output was captured before it died.",
                e,
            )
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
        private const val MULTI_PV = 3
    }
}
