package io.flowforge.common.retry;

import java.time.Duration;

public final class RetryBackoff {

    private RetryBackoff() {
    }

    public static Duration fullJitter(
            Duration baseDelay,
            Duration maximumDelay,
            int attempt,
            double randomFactor) {
        if (baseDelay.isNegative() || maximumDelay.isNegative()) {
            throw new IllegalArgumentException("Retry delays must not be negative");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("Retry attempt must be positive");
        }
        if (!Double.isFinite(randomFactor) || randomFactor < 0 || randomFactor >= 1) {
            throw new IllegalArgumentException("Random factor must be in the range [0, 1)");
        }

        long baseMillis = baseDelay.toMillis();
        long maximumMillis = maximumDelay.toMillis();
        if (baseMillis == 0 || maximumMillis == 0) {
            return Duration.ZERO;
        }

        int shift = Math.min(attempt - 1, 62);
        long ceilingMillis = baseMillis > (maximumMillis >> shift)
                ? maximumMillis
                : Math.min(maximumMillis, baseMillis << shift);
        long delayMillis = (long) (randomFactor * ceilingMillis);
        return Duration.ofMillis(Math.min(delayMillis, ceilingMillis - 1));
    }
}
