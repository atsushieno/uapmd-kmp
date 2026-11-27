#pragma once

// Restores HotSpot's fault handlers on Linux and macOS if the JVM binding
// saved them at startup.
void restoreJvmSignalHandlers();

struct RestoreJvmSignalHandlersOnExit {
    ~RestoreJvmSignalHandlersOnExit() { restoreJvmSignalHandlers(); }
};
