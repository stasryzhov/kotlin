/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "RepeatedTimer.hpp"

#include <atomic>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

using namespace kotlin;

namespace {

template <typename Rep, typename Period>
auto after(std::chrono::duration<Rep, Period> duration) {
    return std::chrono::steady_clock::now() + duration;
}

} // namespace

TEST(RepeatedTimerTest, WillNotExecuteImmediately) {
    std::atomic<int> counter = 0;
    RepeatedTimer timer(after(std::chrono::minutes(10)), [&counter]() {
        ++counter;
        return after(std::chrono::minutes(10));
    });
    // The function is not executed immediately.
    EXPECT_THAT(counter.load(), 0);
}

TEST(RepeatedTimerTest, WillRun) {
    std::atomic<int> counter = 0;
    RepeatedTimer timer(after(std::chrono::milliseconds(10)), [&counter]() {
        ++counter;
        return after(std::chrono::milliseconds(10));
    });
    // Wait until the counter increases at least twice.
    while (counter < 2) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
}

TEST(RepeatedTimerTest, WillStopInDestructor) {
    std::atomic<int> counter = 0;
    {
        RepeatedTimer timer(after(std::chrono::milliseconds(1)), [&counter]() {
            // This lambda will only get executed once.
            EXPECT_THAT(counter.load(), 0);
            ++counter;
            return after(std::chrono::minutes(10));
        });
        // Wait until the counter increases once.
        while (counter < 1) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
    }
    // The destructor was fired and cancelled the timer without executing the function.
    EXPECT_THAT(counter.load(), 1);
}

TEST(RepeatedTimerTest, AdjustInterval) {
    std::atomic<int> counter = 0;
    RepeatedTimer timer(after(std::chrono::milliseconds(1)), [&counter]() {
        ++counter;
        if (counter < 2) {
            return after(std::chrono::milliseconds(1));
        } else {
            return after(std::chrono::minutes(10));
        }
    });
    // Wait until counter grows to 2, when the waiting time changes to 10 minutes.
    while (counter < 2) {
    }
    EXPECT_THAT(counter.load(), 2);
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    // After we've slept for 10ms, we still haven't executed the function another time.
    EXPECT_THAT(counter.load(), 2);
}

TEST(RepeatedTimerTest, NegativeInterval) {
    std::atomic<int> counter = 0;
    RepeatedTimer timer(after(-std::chrono::milliseconds(100)), [&counter]() {
        ++counter;
        return after(std::chrono::seconds(10));
    });
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    EXPECT_THAT(counter.load(), 1);
}

TEST(RepeatedTimerTest, InfiniteInterval) {
    constexpr auto infinite = std::chrono::steady_clock::time_point::max();
    std::atomic<int> counter = 0;
    RepeatedTimer timer(infinite, [&counter, infinite]() {
        ++counter;
        return infinite;
    });
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    EXPECT_THAT(counter.load(), 0);
}

TEST(RepeatedTimerTest, UpdateAt) {
    std::atomic<int> counter = 0;
    auto startAt = std::chrono::steady_clock::now();
    RepeatedTimer timer(startAt + std::chrono::minutes(10), [&counter]() {
        ++counter;
        return after(std::chrono::minutes(10));
    });
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    EXPECT_THAT(counter.load(), 0);

    // Instead of starting after 10 minutes, start after 20ms since the timer creation.
    timer.updateAt(startAt + std::chrono::milliseconds(20));
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    EXPECT_THAT(counter.load(), 1);

    // Function returned starting time of 10 minutes since previous run, so no triggerings anymore.
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    EXPECT_THAT(counter.load(), 1);
}
