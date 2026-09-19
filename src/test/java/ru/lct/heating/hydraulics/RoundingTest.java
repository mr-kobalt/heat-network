package ru.lct.heating.hydraulics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoundingTest {

    @Test
    void roundUp_closeBelowInteger_snapsUp() {
        assertThat(Rounding.roundUp(4.999, 0.01)).isEqualTo(5.0);
    }

    @Test
    void roundUp_significantFraction_unchanged() {
        assertThat(Rounding.roundUp(145.2, 0.01)).isEqualTo(145.2);
    }

    @Test
    void roundUp_zeroTolerance_unchanged() {
        assertThat(Rounding.roundUp(4.999, 0.0)).isEqualTo(4.999);
    }

    @Test
    void roundUp_exactInteger_unchanged() {
        assertThat(Rounding.roundUp(5.0, 0.01)).isEqualTo(5.0);
    }
}
