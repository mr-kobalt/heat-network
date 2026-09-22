package ru.lct.heating.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Фиксирует рабочие дефолты трассировки, выбранные по свипу параметров
 * (ADR-0034, baseline run-28).
 */
class AppPropertiesDefaultsTest {

    private final AppProperties properties = new AppProperties();

    @Test
    void gridForestDefaultsMatchCalibratedBaseline() {
        assertThat(properties.getRoutingAlgorithm()).isEqualTo("grid-forest");
        assertThat(properties.getForestGridCellM()).isEqualTo(2.0);
        assertThat(properties.getForestCostIterations()).isEqualTo(2);
        assertThat(properties.getForestMaxTurnDeg()).isEqualTo(90.0);
        assertThat(properties.getForestTurnEnforcement()).isEqualTo("hard");
        assertThat(properties.getForestRefineLocalPasses()).isEqualTo(2);
        assertThat(properties.isForestExitGridDogleg()).isTrue();
        assertThat(properties.getForestTerminalCellSearch()).isEqualTo("nearest");
        assertThat(properties.isForestTerminalMultiEntry()).isTrue();
        assertThat(properties.isForestReattachPass()).isTrue();
        assertThat(properties.getForestReattachIterations()).isEqualTo(2);
        assertThat(properties.isOksOwningIncludeBoundary()).isTrue();
    }

    @Test
    void storageDefaultsToAutoToKeepPostgisSpillAvailable() {
        assertThat(properties.getForestGridStorage()).isEqualTo("auto");
    }
}
