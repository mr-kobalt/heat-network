package ru.lct.heating.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.RestrictionObject;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.EnvelopeCatalog;
import ru.lct.heating.hydraulics.EnvelopeRow;
import ru.lct.heating.hydraulics.HeatingTablesProperties;

/**
 * E25/ADR-0045: в строгом режиме полоса минимального расстояния вокруг
 * спецобъекта непроходима, пересечение — только через «ворота» перпендикулярно
 * оси; без строгого режима спецобъект для поиска невидим.
 */
class ObstacleIndexBuilderStrictTest {

    @Test
    void strict_blocksParallelTravel_butAllowsGateCrossing() {
        ObstacleIndex strict = build(true);
        // Параллельно оси внутри полосы, между «воротами» — запрещено.
        assertThat(strict.isInteriorBlocked(line(1, 0, 4, 0))).isTrue();
        assertThat(strict.isInteriorBlocked(line(1, 1.0, 4, 1.0))).isTrue();
        // Перпендикулярно через «ворота» (x=5, шаг 5) — разрешено.
        assertThat(strict.isInteriorBlocked(line(5, -5, 5, 5))).isFalse();
    }

    @Test
    void finerGateStep_opensMoreCrossings() {
        HeatingTablesProperties tables = tables();
        AppProperties properties = new AppProperties();
        properties.setForestSpecialStrict(true);
        properties.setSpecialGateStepM(5.0);
        properties.setSpecialGateThicknessM(1.0);
        ObstacleIndexBuilder builder = new ObstacleIndexBuilder(new RestrictionRuleResolver(rules()),
                new EnvelopeCatalog(tables), new DiameterCatalog(tables), properties,
                new SpecialGateCarver(new RestrictionAxisBuilder()));

        ObstacleIndex coarse = builder.build(dataset(), new ArrayList<>());
        ObstacleIndex fine = builder.buildWithGateStep(dataset(), null, 2.5, new ArrayList<>());

        // x=2.5 между «воротами» шагом 5 → заблокировано; шагом 2.5 — открыто.
        assertThat(coarse.isInteriorBlocked(line(2.5, -5, 2.5, 5))).isTrue();
        assertThat(fine.isInteriorBlocked(line(2.5, -5, 2.5, 5))).isFalse();
    }

    @Test
    void nonStrict_ignoresSpecial() {
        ObstacleIndex off = build(false);
        assertThat(off.isInteriorBlocked(line(1, 0, 4, 0))).isFalse();
        assertThat(off.isInteriorBlocked(line(5, -5, 5, 5))).isFalse();
    }

    private ObstacleIndex build(boolean strict) {
        HeatingTablesProperties tables = tables();
        AppProperties properties = new AppProperties();
        properties.setForestSpecialStrict(strict);
        properties.setSpecialGateStepM(5.0);
        properties.setSpecialGateThicknessM(1.0);
        return new ObstacleIndexBuilder(new RestrictionRuleResolver(rules()),
                new EnvelopeCatalog(tables), new DiameterCatalog(tables), properties,
                new SpecialGateCarver(new RestrictionAxisBuilder()))
                .build(dataset(), new ArrayList<>());
    }

    private NetworkDataset dataset() {
        RestrictionObject road = RestrictionObject.builder()
                .id("road1").restrictionType("road").geometry(line(0, 0, 100, 0)).build();
        return NetworkDataset.builder()
                .restrictions(List.of(road))
                .connectionPoints(List.of())
                .networkSegments(List.of())
                .heatChambers(List.of())
                .sources(List.of())
                .oksFutures(List.of())
                .oksExisting(List.of())
                .build();
    }

    private HeatingTablesProperties tables() {
        HeatingTablesProperties tables = new HeatingTablesProperties();
        DiameterRow row = new DiameterRow();
        row.setDn(100);
        row.setCapacityTph(22.3);
        row.setMaxLengthM(419);
        row.setNewCostPerM(89748);
        tables.setDiameters(new ArrayList<>(List.of(row)));
        EnvelopeRow envelope = new EnvelopeRow();
        envelope.setDn(100);
        envelope.setPairWidthM(0.51);
        tables.setEnvelopes(new ArrayList<>(List.of(envelope)));
        return tables;
    }

    private RestrictionRulesProperties rules() {
        RestrictionRulesProperties properties = new RestrictionRulesProperties();
        Map<String, RestrictionRule> rules = new LinkedHashMap<>();
        RestrictionRule road = new RestrictionRule();
        road.setMode(RestrictionMode.SPECIAL);
        road.setMinDistanceM(1.5);
        road.setAngleMinDeg(45.0);
        road.setKSpecial(1.60);
        rules.put("road", road);
        properties.setRules(rules);
        return properties;
    }

    private LineString line(double x1, double y1, double x2, double y2) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }
}
