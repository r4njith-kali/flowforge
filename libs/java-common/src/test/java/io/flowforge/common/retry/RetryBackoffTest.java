package io.flowforge.common.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryBackoffTest {

    @Test
    void jittersTheFirstAttemptWithinItsExponentialCeiling() {
        assertThat(RetryBackoff.fullJitter(
                Duration.ofSeconds(1), Duration.ofMinutes(5), 1, 0.75))
                .isEqualTo(Duration.ofMillis(750));
    }

    @Test
    void doublesTheCeilingForEachAttemptAndAppliesTheRandomFactor() {
        assertThat(RetryBackoff.fullJitter(
                Duration.ofSeconds(1), Duration.ofMinutes(5), 3, 0.5))
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void capsTheExponentialCeilingBeforeApplyingJitter() {
        assertThat(RetryBackoff.fullJitter(
                Duration.ofSeconds(1), Duration.ofSeconds(5), 8, 0.9))
                .isEqualTo(Duration.ofMillis(4500));
    }

    @Test
    void returnsZeroWhenWaitingIsDisabled() {
        assertThat(RetryBackoff.fullJitter(
                Duration.ZERO, Duration.ofSeconds(5), 3, 0.75)).isZero();
        assertThat(RetryBackoff.fullJitter(
                Duration.ofSeconds(1), Duration.ZERO, 3, 0.75)).isZero();
    }

    @Test
    void largeAttemptNumbersDoNotOverflowTheDelayCeiling() {
        assertThat(RetryBackoff.fullJitter(
                Duration.ofSeconds(1), Duration.ofSeconds(5), Integer.MAX_VALUE, 0.5))
                .isEqualTo(Duration.ofMillis(2500));
    }

    @Test
    void validatesAttemptAndRandomFactor() {
        assertThatThrownBy(() -> RetryBackoff.fullJitter(
                Duration.ofSeconds(1), Duration.ofSeconds(5), 0, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryBackoff.fullJitter(
                Duration.ofSeconds(1), Duration.ofSeconds(5), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
