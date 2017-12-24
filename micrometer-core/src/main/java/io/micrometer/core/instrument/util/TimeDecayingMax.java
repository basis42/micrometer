/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.core.instrument.util;

import io.micrometer.core.annotation.Incubating;
import io.micrometer.core.instrument.Clock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

@Incubating(since = "1.0.0-rc.6")
public class TimeDecayingMax {
    private static final int BUFFER_LENGTH = 3;

    private final Clock clock;
    private AtomicLong[] ringBuffer = new AtomicLong[3];

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<TimeDecayingMax> rotatingUpdater =
        AtomicIntegerFieldUpdater.newUpdater(TimeDecayingMax.class, "rotating");

    private final long durationBetweenRotatesMillis;
    private int currentBucket;
    private volatile long lastRotateTimestampMillis;
    @SuppressWarnings({ "unused", "FieldCanBeLocal" })
    private volatile int rotating; // 0 - not rotating, 1 - rotating

    public TimeDecayingMax(Clock clock, long rotateFrequencyMillis) {
        this.clock = clock;
        this.durationBetweenRotatesMillis = rotateFrequencyMillis;

        for(int i = 0; i < BUFFER_LENGTH; i++) {
            ringBuffer[i] = new AtomicLong();
        }
    }

    public void record(double sample, TimeUnit timeUnit) {
        rotate();
        final long sampleNanos = (long) TimeUtils.convert(sample, timeUnit, TimeUnit.NANOSECONDS);
        for (AtomicLong max : ringBuffer) {
            updateMax(max, sampleNanos);
        }
    }

    public double max(TimeUnit timeUnit) {
        rotate();
        synchronized (this) {
            return TimeUtils.nanosToUnit(ringBuffer[currentBucket].get(), timeUnit);
        }
    }

    private void updateMax(AtomicLong max, long sample) {
        for(;;) {
            long curMax = max.get();
            if (curMax >= sample || max.compareAndSet(curMax, sample))
                break;
        }
    }

    private void rotate() {
        long timeSinceLastRotateMillis = clock.wallTime() - lastRotateTimestampMillis;
        if (timeSinceLastRotateMillis < durationBetweenRotatesMillis) {
            // Need to wait more for next rotation.
            return;
        }

        if (!rotatingUpdater.compareAndSet(this, 0, 1)) {
            // Being rotated by other thread already.
            return;
        }

        try {
            synchronized (this) {
                do {
                    ringBuffer[currentBucket].set(0);
                    if (++currentBucket >= ringBuffer.length) {
                        currentBucket = 0;
                    }
                    timeSinceLastRotateMillis -= durationBetweenRotatesMillis;
                    lastRotateTimestampMillis += durationBetweenRotatesMillis;
                } while (timeSinceLastRotateMillis >= durationBetweenRotatesMillis);
            }
        } finally {
            rotating = 0;
        }
    }
}
