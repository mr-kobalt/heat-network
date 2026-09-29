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
        assertThat(properties.getForestGridCellM()).isEqualTo(1.0);
        assertThat(properties.getForestGridShape()).isEqualTo("hex");
        assertThat(properties.getForestCostIterations()).isEqualTo(2);
        assertThat(properties.getForestMaxTurnDeg()).isEqualTo(90.0);
        assertThat(properties.getForestTurnEnforcement()).isEqualTo("hard");
        assertThat(properties.getForestRefineLocalPasses()).isEqualTo(2);
        assertThat(properties.isForestExitGridDogleg()).isTrue();
        assertThat(properties.isForestReattachPass()).isTrue();
        assertThat(properties.getForestReattachIterations()).isEqualTo(2);
        assertThat(properties.isForestRelinkNodes()).isTrue();
        assertThat(properties.getForestRelinkNodesRadiusM()).isEqualTo(100.0);
        assertThat(properties.getForestMaxChamberDegree()).isEqualTo(4);
        assertThat(properties.isForestRelinkExitRelocation()).isFalse();
        assertThat(properties.getForestRelinkExitCandidatesMax()).isEqualTo(6);
        assertThat(properties.isOksOwningIncludeBoundary()).isTrue();
        assertThat(properties.isOksExitFilter()).isTrue();
        assertThat(properties.getOksExitMaxTailM()).isEqualTo(15.0);
        assertThat(properties.getTieInSampleStepM()).isEqualTo(1.0);
        assertThat(properties.getTieInChamberExclusionM()).isEqualTo(1.0);
        assertThat(properties.isForestGridIncludeInputBounds()).isTrue();
        assertThat(properties.isForestDiameterOptimizer()).isTrue();
        assertThat(properties.isForestExitVisibilityFallback()).isTrue();
        assertThat(properties.getForestExitVisibilityMinDetourM()).isEqualTo(2.0);
        assertThat(properties.getForestExitVisibilityMaxDetourM()).isEqualTo(10.0);
        assertThat(properties.getForestExitVisibilityMaxAttempts()).isEqualTo(200);
        assertThat(properties.getForestExitVisibilityMaxNodes()).isEqualTo(200);
        assertThat(properties.isForestExitRegularization()).isTrue();
        assertThat(properties.getForestExitSnapM()).isEqualTo(1.0);
        assertThat(properties.getForestExitMicroM()).isEqualTo(0.5);
    }

    @Test
    void depthModeDefaultsMatchTpV2() {
        assertThat(properties.getDepthNormalM()).isEqualTo(3.0);
        assertThat(properties.getDepthMinM()).isEqualTo(0.7);
        assertThat(properties.getDepthMaxSlope()).isEqualTo(0.10);
        assertThat(properties.getDepthVerticalClearanceM()).isEqualTo(0.2);
        assertThat(properties.getDepthCloseCrossingM()).isEqualTo(5.0);
    }

    @Test
    void storageDefaultsToAutoToKeepPostgisSpillAvailable() {
        assertThat(properties.getForestGridStorage()).isEqualTo("auto");
    }
}
