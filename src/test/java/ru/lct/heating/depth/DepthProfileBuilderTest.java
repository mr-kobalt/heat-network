package ru.lct.heating.depth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.geometry.SpecialSpan;
import ru.lct.heating.hydraulics.EnvelopeCatalog;
import ru.lct.heating.hydraulics.EnvelopeRow;
import ru.lct.heating.hydraulics.HeatingTablesProperties;

/**
 * ADR-0073 / ТП v2 §5: профиль глубины — обычная отметка, обход коммуникаций с
 * уклоном ≤ 0,10 м/м, минимальная глубина 0,7 м, {@code Kгл}, объединение
 * близких пересечений.
 */
class DepthProfileBuilderTest {

    private final AppProperties properties = new AppProperties();

    private DepthProfileBuilder builder() {
        return new DepthProfileBuilder(properties, new EnvelopeCatalog(tables(0.18)));
    }

    @Test
    void flatProfileWhenNoVerticalObstacles() {
        List<DepthSegment> profile = builder().build(50.0, 100, List.of(), new ArrayList<>());

        assertThat(profile).hasSize(1);
        DepthSegment only = profile.get(0);
        assertThat(only.getStartDistanceM()).isEqualTo(0.0);
        assertThat(only.getEndDistanceM()).isEqualTo(50.0);
        assertThat(only.getDepthStartM()).isEqualTo(3.0);
        assertThat(only.getDepthEndM()).isEqualTo(3.0);
        assertThat(only.getKDepth()).isEqualTo(1.0);
    }

    @Test
    void deviatesAboveGasAndKeepsDepthCoefficientOne() {
        SpecialSpan gas = span(20.0, 25.0, "gas_pipeline", 2.8, 0.4);

        List<DepthSegment> profile = builder().build(40.0, 100, List.of(gas), new ArrayList<>());

        assertThat(profile.get(0).getDepthStartM()).isEqualTo(3.0);
        assertContinuous(profile, 40.0);
        // Обход сверху: 2,8 − 0,18 (габарит) − 0,2 (зазор) = 2,42 м; уклон 0,10 → 5,8 м.
        DepthSegment hold = profile.stream()
                .filter(segment -> Math.abs(segment.getDepthStartM() - segment.getDepthEndM()) < 1e-9)
                .filter(segment -> Math.abs(segment.getDepthStartM() - 2.42) < 1e-6)
                .findFirst().orElseThrow(AssertionError::new);
        assertThat(hold.getStartDistanceM()).isEqualTo(20.0);
        assertThat(hold.getEndDistanceM()).isEqualTo(25.0);
        assertThat(hold.getKDepth()).isEqualTo(1.0);
        assertSlopeWithin(profile, 0.10);
    }

    @Test
    void deepBelowDeviationUsesDepthCoefficient() {
        properties.setDepthMinM(2.9);
        SpecialSpan gas = span(20.0, 25.0, "gas_pipeline", 2.8, 0.4);

        List<DepthSegment> profile = builder().build(60.0, 100, List.of(gas), new ArrayList<>());

        // Выше нельзя (2,42 < 2,9), уходим ниже: 3,2 + 0,2 = 3,4 м, Kгл = 1,04.
        DepthSegment hold = profile.stream()
                .filter(segment -> Math.abs(segment.getDepthStartM() - 3.4) < 1e-6)
                .findFirst().orElseThrow(AssertionError::new);
        assertThat(hold.getKDepth()).isEqualTo(1.04);
        assertSlopeWithin(profile, 0.10);
    }

    @Test
    void mergesCloseCrossingsWithoutReturnToNormal() {
        SpecialSpan first = span(20.0, 22.0, "gas_pipeline", 2.8, 0.4);
        SpecialSpan second = span(25.0, 27.0, "gas_pipeline", 2.8, 0.4);

        List<DepthSegment> profile = builder().build(60.0, 100, List.of(first, second),
                new ArrayList<>());

        long holds = profile.stream()
                .filter(segment -> Math.abs(segment.getDepthStartM() - 2.42) < 1e-6
                        && Math.abs(segment.getDepthEndM() - 2.42) < 1e-6)
                .count();
        assertThat(holds).isEqualTo(1);
        assertContinuous(profile, 60.0);
        assertSlopeWithin(profile, 0.10);
    }

    @Test
    void ignoresTypesWithoutVerticalGauge() {
        SpecialSpan road = SpecialSpan.builder()
                .startDistanceM(10.0).endDistanceM(20.0).kSpecial(1.6)
                .restrictionType("road").build();

        List<DepthSegment> profile = builder().build(40.0, 100, List.of(road), new ArrayList<>());

        assertThat(profile).hasSize(1);
        assertThat(profile.get(0).getDepthStartM()).isEqualTo(3.0);
    }

    private void assertContinuous(List<DepthSegment> profile, double length) {
        assertThat(profile.get(0).getStartDistanceM()).isEqualTo(0.0);
        assertThat(profile.get(profile.size() - 1).getEndDistanceM()).isEqualTo(length);
        for (int i = 1; i < profile.size(); i++) {
            DepthSegment previous = profile.get(i - 1);
            DepthSegment current = profile.get(i);
            assertThat(current.getStartDistanceM()).isEqualTo(previous.getEndDistanceM());
            assertThat(current.getDepthStartM())
                    .as("непрерывность глубины на стыке %s", i)
                    .isEqualTo(previous.getDepthEndM());
        }
    }

    private void assertSlopeWithin(List<DepthSegment> profile, double maxSlope) {
        for (DepthSegment segment : profile) {
            double run = segment.lengthM();
            double drop = Math.abs(segment.getDepthEndM() - segment.getDepthStartM());
            if (run > 1e-9) {
                assertThat(drop / run).isLessThanOrEqualTo(maxSlope + 1e-6);
            }
        }
    }

    private SpecialSpan span(double start, double end, String type, double top, double height) {
        return SpecialSpan.builder()
                .startDistanceM(start).endDistanceM(end).kSpecial(1.0)
                .restrictionType(type).verticalTopDepthM(top).verticalHeightM(height)
                .build();
    }

    private HeatingTablesProperties tables(double height) {
        HeatingTablesProperties tables = new HeatingTablesProperties();
        List<EnvelopeRow> rows = new ArrayList<>();
        EnvelopeRow row = new EnvelopeRow();
        row.setDn(100);
        row.setHeightM(height);
        row.setPairWidthM(0.51);
        rows.add(row);
        tables.setEnvelopes(rows);
        return tables;
    }
}
