#include "jvm-signal-handlers.h"
#include "c-api/uapmd-c-common.h"

#if defined(__APPLE__)
#include <TargetConditionals.h>
#endif

#if (defined(TARGET_OS_OSX) && TARGET_OS_OSX) || (defined(__linux__) && !defined(__ANDROID__))
#define UAPMD_HAS_HOTSPOT_POSIX_SIGNALS 1
#include <array>
#include <atomic>
#include <dlfcn.h>
#include <signal.h>
#include <string_view>

namespace {

struct JvmSignalHandler {
    int signal;
    struct sigaction action{};
};

using JvmSignalHandlers = std::array<JvmSignalHandler, 4>;
std::atomic<const JvmSignalHandlers*> jvmSignalHandlers{};

bool isHotSpotHandler(const struct sigaction& action) {
#if defined(__APPLE__)
    constexpr std::string_view library = "/libjvm.dylib";
#else
    constexpr std::string_view library = "/libjvm.so";
#endif
    Dl_info info{};
    return (action.sa_flags & SA_SIGINFO) &&
        dladdr(reinterpret_cast<void*>(action.sa_sigaction), &info) &&
        info.dli_fname && std::string_view(info.dli_fname).ends_with(library);
}

bool sameSignalMask(const sigset_t& current, const sigset_t& saved) {
#if defined(__APPLE__)
    return current == saved;
#else
    // Linux's sigset_t can contain padding that sigaction does not populate.
    for (int signal = 1; signal < NSIG; ++signal)
        if (sigismember(&current, signal) != sigismember(&saved, signal))
            return false;
    return true;
#endif
}

} // namespace
#endif

// Native plug-ins can install process-wide crash handlers. HotSpot uses these
// signals during ordinary execution, so save its dispositions before any
// plug-ins are loaded and restore them at the native call boundaries.
extern "C" UAPMD_C_EXPORT void uapmd_internal_preserve_jvm_signal_handlers() {
#if defined(UAPMD_HAS_HOTSPOT_POSIX_SIGNALS)
    static const auto handlers = [] {
        JvmSignalHandlers saved{{{SIGSEGV}, {SIGBUS}, {SIGILL}, {SIGFPE}}};
        for (auto& [signal, action] : saved)
            if (sigaction(signal, nullptr, &action) != 0 || !isHotSpotHandler(action))
                signal = 0;
        return saved;
    }();
    jvmSignalHandlers.store(&handlers);
#endif
}

void restoreJvmSignalHandlers() {
#if defined(UAPMD_HAS_HOTSPOT_POSIX_SIGNALS)
    const auto* handlers = jvmSignalHandlers.load();
    if (!handlers)
        return;
    for (const auto& [signal, saved] : *handlers) {
        struct sigaction current{};
        if (signal && sigaction(signal, nullptr, &current) == 0 &&
            (current.sa_flags != saved.sa_flags || current.sa_sigaction != saved.sa_sigaction ||
             !sameSignalMask(current.sa_mask, saved.sa_mask)))
            sigaction(signal, &saved, nullptr);
    }
#endif
}
