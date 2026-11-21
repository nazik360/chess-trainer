cmake_minimum_required(VERSION 3.22.1)
project(chesstrainer_native CXX)

# ---------------------------------------------------------------------------
# Builds the vendored Stockfish sources (app/src/main/cpp/stockfish/src, taken
# from https://github.com/official-stockfish/Stockfish) directly, instead of
# invoking Stockfish's own Makefile. That keeps the Android build fully inside
# Gradle/CMake and lets us compile straight from the upstream .cpp files
# whenever you refresh them.
#
# NNUE_EMBEDDING_OFF: Stockfish normally embeds its default network file into
# the binary at compile time via incbin, which means the exact .nnue file has
# to be sitting on disk *during compilation*. We turn that off and load the
# network at runtime instead, via the UCI "EvalFile" option
# (see NetworkManager.kt): the app downloads the official network the first
# time it runs, on the phone's own connection, and points the engine at it.
# ---------------------------------------------------------------------------

set(SF_SRC ${CMAKE_CURRENT_SOURCE_DIR}/stockfish/src)

set(STOCKFISH_SOURCES
    ${SF_SRC}/attacks.cpp
    ${SF_SRC}/benchmark.cpp
    ${SF_SRC}/bitboard.cpp
    ${SF_SRC}/engine.cpp
    ${SF_SRC}/evaluate.cpp
    ${SF_SRC}/main.cpp
    ${SF_SRC}/memory.cpp
    ${SF_SRC}/misc.cpp
    ${SF_SRC}/movegen.cpp
    ${SF_SRC}/movepick.cpp
    ${SF_SRC}/nnue/features/full_threats.cpp
    ${SF_SRC}/nnue/features/k32h3.cpp
    ${SF_SRC}/nnue/features/pp_3wide.cpp
    ${SF_SRC}/nnue/network.cpp
    ${SF_SRC}/nnue/nnue_accumulator.cpp
    ${SF_SRC}/nnue/nnue_misc.cpp
    ${SF_SRC}/position.cpp
    ${SF_SRC}/score.cpp
    ${SF_SRC}/search.cpp
    ${SF_SRC}/syzygy/tbprobe.cpp
    ${SF_SRC}/thread.cpp
    ${SF_SRC}/timeman.cpp
    ${SF_SRC}/tt.cpp
    ${SF_SRC}/tune.cpp
    ${SF_SRC}/uci.cpp
    ${SF_SRC}/ucioption.cpp
)

# IMPORTANT: this is really an executable, not a library — we talk to it over
# stdin/stdout UCI, we never dlopen() it. It's named and suffixed like a
# shared library purely so Android's packager extracts it into
# nativeLibraryDir with the executable bit set (Android 10+ forbids executing
# arbitrary files placed anywhere else, per its W^X policy). See
# EngineProcess.kt for how it's actually launched.
add_executable(stockfish-engine ${STOCKFISH_SOURCES})

set_target_properties(stockfish-engine PROPERTIES
    PREFIX "lib"
    OUTPUT_NAME "stockfish-engine"
    SUFFIX ".so"
)

target_include_directories(stockfish-engine PRIVATE ${SF_SRC})

target_compile_features(stockfish-engine PRIVATE cxx_std_17)
target_compile_options(stockfish-engine PRIVATE
    -fno-exceptions
    -O3
    -DNDEBUG
    -DNNUE_EMBEDDING_OFF
    -DIS_64BIT
    -DUSE_POPCNT
)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Baseline ARMv8 NEON. If you only target phones from the last few years
    # you can switch this to "-march=armv8.2-a+dotprod -DUSE_NEON_DOTPROD"
    # for a meaningful eval speedup (most 2019+ SoCs support it).
    target_compile_options(stockfish-engine PRIVATE -DUSE_NEON=8)
elseif(ANDROID_ABI STREQUAL "x86_64")
    # x86_64 Android emulator / Chromebooks. Conservative baseline (SSE4.1
    # level); bump to -DUSE_AVX2 -mavx2 -mbmi if you know the target supports it.
    target_compile_options(stockfish-engine PRIVATE -msse4.1 -DUSE_SSE41 -mpopcnt)
endif()

find_library(log-lib log)
target_link_libraries(stockfish-engine ${log-lib})
