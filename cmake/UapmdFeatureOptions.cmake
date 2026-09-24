# Feature options for the uapmd engine built out of external/uapmd.
#
# uapmd itself defaults most of these OFF, because a default build should not
# silently take on the dependencies behind them: librosa.cpp and demucs.cpp
# bring in MPL-2.0 Eigen, ARA and Basic Pitch are Apache-2.0, and Basic Pitch
# additionally downloads model weights at configure time and embeds them in the
# binary, which is a redistribution decision rather than a build one.
#
# uapmd-kmp exists to bind the whole uapmd API surface, and the addins these
# options build (MIR analysis, stem separation, pitch and drum transcription,
# ARA hosting, Augene2 MML integration) are part of that surface: with them off, the extension points the
# bindings publish would have nothing to attach to and the bindings could never
# be exercised. So every feature option is ON here, and c-api/ binds what they
# produce.
#
# Declared with option(), so a cache entry created by -D on the command line
# still wins -- the Android build relies on that to force ARA off.
#
# Deliberately NOT set here: UAPMD_ENABLE_ASAN and
# UAPMD_ENABLE_HEAVY_AUDIO_FEATURE_DEBUG are build-diagnostics knobs rather than
# features, and turning them on would only make every build slower.

include_guard(GLOBAL)

option(UAPMD_ENABLE_LIBROSA_CPP
        "Build against librosa.cpp (ISC, fetches MPL-2.0 Eigen)" ON)
option(UAPMD_ENABLE_MIR
        "Build the music-analysis addins in uapmd-mir (tempo, meter, chords)" ON)
option(UAPMD_ENABLE_LIBSONARE
        "Build the libsonare MIR backend (requires UAPMD_ENABLE_MIR)" ON)
option(UAPMD_ENABLE_DEMUCS_CPP
        "Build the Demucs stem separation addin (demucs.cpp, vendors MPL-2.0 Eigen)" ON)
option(UAPMD_ENABLE_BASIC_PITCH
        "Build the Basic Pitch polyphonic transcription addin (Apache-2.0, downloads model weights)" ON)
option(UAPMD_ENABLE_DRUMSCRIPT
        "Build the DrumScript drum transcription addin (Apache-2.0, requires UAPMD_ENABLE_LIBROSA_CPP)" ON)

# ysfx is Apache-2.0, the same category as ARA and Basic Pitch above, and JSFX is a
# plugin format the bindings publish like any other: with it off there is no JSFX in
# the catalogue for them to bind to. Its editor is a framebuffer rather than a native
# window, which is what the framebuffer UI entry points in c-api/ exist for.
# Off on Android for now: augene2's ANTLR 4.13.2 C++ runtime inherits uapmd's C++23
# and fails to compile against NDK r28 libc++ (ParseTreePatternMatcher.cpp deletes an
# incomplete antlr4::Token). It builds as C++17/20; the fix belongs in augene2. The
# binding still links there and reports Augene2 as unavailable.
set(_UAPMD_KMP_AUGENE2_DEFAULT ON)
if(ANDROID)
    set(_UAPMD_KMP_AUGENE2_DEFAULT OFF)
endif()
option(UAPMD_ENABLE_AUGENE2
        "Build the Augene2 MML integration addin (MIT; augene2's own build runs ANTLR)" ${_UAPMD_KMP_AUGENE2_DEFAULT})
option(UAPMD_ENABLE_JSFX
        "Build the JSFX plugin format (Apache-2.0 ysfx, zlib-licensed WDL/EEL2)" ON)

# Windows MIDI Services is Windows-only; matching uapmd's own default keeps the
# option present (and visible in the cache) on every platform.
option(UAPMD_ENABLE_WINMIDI
        "Enable Windows MIDI Services backend integration (Windows only)" ${WIN32})

# ARA_API has no ABI packing definition for 32-bit ARM or for wasm32 and fails
# to compile there outright, so the default follows the target rather than the
# policy above. uapmd guards Emscripten itself; the 32-bit Android ABIs it does
# not.
set(_UAPMD_KMP_ARA_DEFAULT ON)
if(ANDROID AND NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    set(_UAPMD_KMP_ARA_DEFAULT OFF)
endif()
option(UAPMD_ENABLE_ARA
        "Build the optional ARA host integration (Apache-2.0)" ${_UAPMD_KMP_ARA_DEFAULT})
