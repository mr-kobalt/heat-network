package ru.lct.heating.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.domain.RestrictionObject;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.EnvelopeCatalog;
import ru.lct.heating.hydraulics.EnvelopeRow;
import ru.lct.heating.hydraulics.HeatingTablesProperties;

/**
 * E25-05b: {@code buildAtDiameter} увеличивает отступ `oks` по фактическому Ду
 * (5/7/9 м), тогда как базовый {@code build} использует минимальный Ду.
 */
class ObstacleIndexBuilderDiameterTest {

    @Test
    void buildAtDiameter_enlargesOksSetback() {
        HeatingTablesProperties tables = tables();
        AppProperties properties = new AppProperties();
        ObstacleIndexBuilder builder = new ObstacleIndexBuilder(new RestrictionRuleResolver(rules()),
                new EnvelopeCatalog(tables), new DiameterCatalog(tables), properties,
                new SpecialGateCarver(new RestrictionAxisBuilder()));

        ObstacleIndex base = builder.build(dataset(), new ArrayList<>());
        ObstacleIndex wide = builder.buildAtDiameter(dataset(), 1000, new ArrayList<>());

        // 7 м от границы полигона: при Ду 50 (5 м) не блокируется, при Ду 1000 (9 м) — да.
        LineString near = line(107, 40, 107, 60);
        assertThat(base.isInteriorBlocked(near)).isFalse();
        assertThat(wide.isInteriorBlocked(near)).isTrue();
        // Вплотную блокируется в обоих режимах.
        assertThat(base.isInteriorBlocked(line(101, 40, 101, 60))).isTrue();
    }

    private NetworkDataset dataset() {
        Polygon oks = GeometrySupport.GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(100, 0), new Coordinate(100, 100),
                new Coordinate(0, 100), new Coordinate(0, 0)});
        RestrictionObject restriction = RestrictionObject.builder()
                .id("oks1").restrictionType("oks").geometry(oks).build();
        OksConnectionPointObject point = OksConnectionPointObject.builder()
                .id("cp1").flowTph(10.0)
                .geometry(GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(10, 50)))
                .build();
        return NetworkDataset.builder()
                .restrictions(List.of(restriction))
                .connectionPoints(List.of(point))
                .networkSegments(List.of())
                .heatChambers(List.of())
                .sources(List.of())
                
                .build();
    }

    private HeatingTablesProperties tables() {
        HeatingTablesProperties tables = new HeatingTablesProperties();
        DiameterRow row = new DiameterRow();
        row.setDn(50);
        row.setCapacityTph(3.5);
        row.setMaxLengthM(181);
        row.setNewCostPerM(74023);
        tables.setDiameters(new ArrayList<>(List.of(row)));
        EnvelopeRow envelope = new EnvelopeRow();
        envelope.setDn(50);
        envelope.setPairWidthM(0.4);
        tables.setEnvelopes(new ArrayList<>(List.of(envelope)));
        return tables;
    }

    private RestrictionRulesProperties rules() {
        RestrictionRulesProperties properties = new RestrictionRulesProperties();
        Map<String, RestrictionRule> rules = new LinkedHashMap<>();
        RestrictionRule oks = new RestrictionRule();
        oks.setMode(RestrictionMode.PROHIBITED);
        oks.setMinDistanceM(5.0);
        List<DistanceBand> bands = new ArrayList<>();
        bands.add(band(499, 5.0));
        bands.add(band(800, 7.0));
        bands.add(band(100000, 9.0));
        oks.setDistanceBands(bands);
        rules.put("oks", oks);
        properties.setRules(rules);
        return properties;
    }

    private DistanceBand band(int maxDn, double distance) {
        DistanceBand band = new DistanceBand();
        band.setMaxDn(maxDn);
        band.setDistanceM(distance);
        return band;
    }

    private LineString line(double x1, double y1, double x2, double y2) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }
}
