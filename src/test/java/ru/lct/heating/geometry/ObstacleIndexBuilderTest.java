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
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.domain.RestrictionObject;
import ru.lct.heating.hydraulics.EnvelopeCatalog;
import ru.lct.heating.hydraulics.HeatingTablesProperties;

class ObstacleIndexBuilderTest {

    @Test
    void build_oksPolygonContainingPoint_carvesFinalCorridor() {
        Polygon oks = GeometrySupport.GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(100, 0), new Coordinate(100, 100),
                new Coordinate(0, 100), new Coordinate(0, 0)});
        RestrictionObject restriction = RestrictionObject.builder()
                .id("oks1").restrictionType("oks").geometry(oks).build();
        OksConnectionPointObject point = OksConnectionPointObject.builder()
                .id("cp1").flowTph(10.0)
                .geometry(GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(10, 50)))
                .build();
        NetworkDataset dataset = NetworkDataset.builder()
                .restrictions(List.of(restriction))
                .connectionPoints(List.of(point))
                .networkSegments(List.of())
                .heatChambers(List.of())
                .sources(List.of())
                .oksFutures(List.of())
                .oksExisting(List.of())
                .build();

        ObstacleIndex index = new ObstacleIndexBuilder(
                new RestrictionRuleResolver(rules()), new EnvelopeCatalog(new HeatingTablesProperties()))
                .build(dataset, 100, new ArrayList<>());

        assertThat(index.isBlocked(line(10, 50, 0, 50))).isFalse();
        assertThat(index.isBlocked(line(10, 50, 10, 90))).isTrue();
        assertThat(index.isBlocked(line(60, 50, 60, 90))).isTrue();
    }

    private RestrictionRulesProperties rules() {
        RestrictionRulesProperties properties = new RestrictionRulesProperties();
        Map<String, RestrictionRule> rules = new LinkedHashMap<>();
        RestrictionRule oks = new RestrictionRule();
        oks.setMode(RestrictionMode.PROHIBITED);
        oks.setMinDistanceM(5.0);
        rules.put("oks", oks);
        properties.setRules(rules);
        return properties;
    }

    private LineString line(double x1, double y1, double x2, double y2) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }
}
