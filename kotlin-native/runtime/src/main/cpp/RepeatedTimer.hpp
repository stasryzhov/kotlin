/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#ifndef RUNTIME_REPEATED_TIMER_H
#define RUNTIME_REPEATED_TIMER_H

#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>

#include "KAssert.h"
#include "Utils.hpp"

namespace kotlin {

template <typename Clock, typename Duration = typename Clock::duration>
class RepeatedTimer : private Pinned {
public:
    template <typename F>
    RepeatedTimer(std::chrono::time_point<Clock, Duration> at, F f) noexcept : at_(at), thread_([this, f]() noexcept { Run(f); }) {}

    ~RepeatedTimer() {
        {
            std::unique_lock lock(mutex_);
            shutdownRequested_ = true;
        }
        wait_.notify_all();
        thread_.join();
    }

    void updateAt(std::chrono::time_point<Clock, Duration> at) noexcept {
        {
            std::unique_lock lock(mutex_);
            at_ = at;
        }
        wait_.notify_all();
    }

private:
    template <typename F>
    void Run(F f) noexcept {
        while (true) {
            std::unique_lock lock(mutex_);
            auto at = at_;
            if (wait_.wait_until(lock, at, [this, at]() noexcept { return shutdownRequested_ || at != at_; })) {
                if (shutdownRequested_) {
                    return;
                }
                // Otherwise at_ must've changed, get it and restart waiting.
                continue;
            }
            RuntimeAssert(!shutdownRequested_, "Can only happen if we timed out on waiting and run_ is still true");
            at_ = std::chrono::time_point_cast<Duration>(f());
        }
    }

    std::mutex mutex_;
    std::condition_variable wait_;
    bool shutdownRequested_ = false;
    std::chrono::time_point<Clock, Duration> at_;
    std::thread thread_;
};

} // namespace kotlin

#endif // RUNTIME_REPEATED_TIMER_H
