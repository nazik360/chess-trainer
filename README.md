# Chess Trainer (Stockfish overlay)

Android app that recognizes a chess position shown on screen (in whatever
chess app/site you have open) and draws a floating arrow suggesting
Stockfish's best move - **for personal training and post-game analysis**,
not for use during a rated/competitive game (see "Ethics" below).

It works entirely on-device: no server, no cloud OCR, no account. The chess
engine is [Stockfish](https://github.com/official-stockfish/Stockfish),
compiled from source straight into the APK via CMake/NDK.

## How it works

1. **MediaProjection screen capture** (`CaptureService`) grabs a screenshot
   every ~1.5s.
2. **Calibration** (`CalibrationActivity`) - a one-time step per chess app/
   board theme - lets you drag 4 handles onto the board's corners on a frozen
   screenshot, and then captures a small visual "fingerprint" for each of the
   12 piece types + 2 empty-square colors from a screenshot of the standard
   starting position.
3. **Board recognition** (`BoardRecognizer`) perspective-warps each new frame
   to a flat square and classifies each of the 64 cells against those
   fingerprints (nearest-neighbour matching - no ML model, no internet).
4. The recognized board becomes a FEN string (`FenBuilder`), sent to a real
   **Stockfish** process (`EngineProcess`, talking UCI over stdin/stdout).
5. The best move is drawn as a floating arrow (`OverlayService`), on top of
   whatever app is in front.

Because step 3 is template matching (not a generic vision model), it only
works reliably for **one visual board/piece theme at a time** - recalibrate
(just the "Capture piece templates" step) if you switch to a different
board skin.

## Building

You need [Android Studio](https://developer.android.com/studio) (which
bundles the Android SDK) and the **NDK** (Android Studio: Settings ▸
Languages & Frameworks ▸ Android SDK ▸ SDK Tools ▸ check "NDK (Side by
side)", version 26.3.11579264 or update `app/build.gradle.kts`'s `ndkVersion`
to match what you have installed).

```
1. Open this folder in Android Studio ("Open an existing project").
2. Let Gradle sync (first sync will also download the NDK/CMake if you
   checked those SDK Manager boxes).
3. Run ▸ Run 'app' on a connected device or emulator (minSdk 26 / Android 8+).
```

Or from the command line, once `ANDROID_HOME`/`ANDROID_SDK_ROOT` is set and
the NDK is installed:

```
./gradlew assembleDebug
# APK lands at app/build/outputs/apk/debug/app-debug.apk
```

There is no `gradlew`/`gradle-wrapper.jar` checked in (this environment
couldn't reach `services.gradle.org` to generate one) - run
`gradle wrapper --gradle-version 8.14.3` once from this folder with a local
Gradle install to generate it, or just use Android Studio, which doesn't
need the wrapper.

### Where the Stockfish source came from

`app/src/main/cpp/stockfish/src` is vendored from
<https://github.com/official-stockfish/Stockfish> (commit `1819b9ea`, dev
build `stockfish-dev-20261120`, 2026-11-20). `app/src/main/cpp/CMakeLists.txt`
compiles those `.cpp` files directly (rather than invoking Stockfish's own
Makefile) with `NNUE_EMBEDDING_OFF`, because the usual approach embeds the
~140MB neural network file into the binary at compile time, which would
bloat the APK and require re-downloading that file on every build. Instead,
the app downloads the network once, on the phone itself
(`NetworkManager.kt`), the first time you use it, and points the engine at
it via the UCI `EvalFile` option at runtime.

To update to a newer Stockfish: replace `app/src/main/cpp/stockfish/src`
with a fresh checkout of `src/`, check whether the list of `.cpp` files in
`CMakeLists.txt` still matches (diff `src/Makefile`'s `OBJS`/source list),
and update `NetworkManager.DEFAULT_NET_NAME` to match the new
`EvalFileDefaultName` in `evaluate.h`.

## Using it

1. Launch the app, work through the three setup steps in order: grant the
   "draw over other apps" permission, download the engine network file, then
   calibrate.
2. **Calibrate**: tapping "Calibrate board" starts screen capture + the
   floating control panel. Switch to your chess app so the board is showing,
   then tap **Calibrate** on the floating panel - this freezes the current
   screen and opens the corner-dragging screen.
   - Drag the four red handles onto the board's actual corners. Toggle
     "Black at bottom" if the board is flipped.
   - Tap **Save corners**, then make sure that same frozen screenshot shows
     the **standard starting position** (if it doesn't, back out, reset the
     board in your chess app to the start, and redo calibration) and tap
     **Capture piece templates**.
3. Back on the main screen, tap **Start session**. A small floating panel
   appears with:
   - a side-to-move toggle (the app can't reliably detect whose turn it is
     from pixels alone - tell it),
   - Pause/Resume,
   - Calibrate (redo calibration without restarting the session),
   - Stop.
4. Whenever it isn't paused, the floating arrow shows Stockfish's
   recommended move for the current position, plus the evaluation and search
   depth reached in the ~1.2s thinking time per move (`SessionSettings` in
   code - change `moveTimeMs`/`captureIntervalMs` there if you want deeper
   but slower analysis).

## Known limitations

- Side-to-move, castling-rights edge cases (a king/rook that moved and moved
  back), and en-passant are not derived from the position (see
  `FenBuilder`'s doc comment) - this is a training aid, not a tournament PGN
  recorder.
- Recognition accuracy depends entirely on the calibration quality and how
  visually distinct your board theme's pieces are; busy/3D board skins with
  shiny pieces will do worse than flat, high-contrast ones (the default
  themes on chess.com/Lichess work well).
- Only `arm64-v8a` and `x86_64` are built (see `abiFilters` in
  `app/build.gradle.kts`) - essentially every phone sold since ~2018. Add
  `armeabi-v7a` there (and a matching ABI block in `CMakeLists.txt`) if you
  need 32-bit ARM support.
- The on-device NNUE download currently targets the specific file named in
  `NetworkManager.DEFAULT_NET_NAME`; if that mirror set goes away you'll need
  to update the URLs there.

## Ethics

This exists for studying your own games and practicing against/with
Stockfish - **not** for getting moves fed to you during a game you're being
rated or judged on. Every major chess platform (chess.com, Lichess, etc.)
explicitly bans any engine assistance during rated play and does detect it
statistically; using a tool like this live, over the board or online, in a
game that counts is cheating, full stop. Please keep it to analysis and
training.
