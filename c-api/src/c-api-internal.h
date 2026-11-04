/* Internal shared declarations for uapmd C-API translation units */
#pragma once

#include <chrono>
#include <future>
#include <mutex>
#include <memory>
#include <thread>
#include <unordered_map>
#include <remidy/detail/event-loop.hpp>

namespace uapmd { class AudioFileReader; }

/* AudioFileReader ownership registry — defined in uapmd-c-data.cpp */
extern std::mutex s_reader_mutex;
extern std::unordered_map<uapmd::AudioFileReader*, std::unique_ptr<uapmd::AudioFileReader>> s_owned_readers;

/* Waits for a project operation. It posts its steps and completions to the event loop's main
 * thread, so a wait on that thread has to drain the queue or it would never finish. */
template <typename T>
T waitDrainingMainThread(std::future<T> future) {
    while (future.wait_for(std::chrono::seconds(0)) != std::future_status::ready) {
        if (remidy::EventLoop::runningOnMainThread())
            remidy::EventLoop::processQueuedTasks();
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    return future.get();
}
