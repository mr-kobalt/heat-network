package ru.lct.heating.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.distance.DistanceOp;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.domain.RestrictionObject;
import ru.lct.heating.geometry.RestrictionMode;
import ru.lct.heating.geometry.RestrictionRule;
import ru.lct.heating.geometry.RestrictionRuleResolver;
import ru.lct.heating.geometry.RestrictionRulesProperties;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.EnvelopeCatalog;
import ru.lct.heating.hydraulics.EnvelopeRow;
import ru.lct.heating.hydraulics.HeatingTablesProperties;

class OksApproachResolverTest {

    private static final int DN = 100;
    private static final double OFFSET = 5.255;
    private static final double CLEARANCE = 1.0;

    private final OksApproachResolver resolver = new OksApproachResolver(
            new RestrictionRuleResolver(rules()),
            new EnvelopeCatalog(tables()), new DiameterCatalog(tables()), new AppProperties());

    @Test
    void resolve_nearestPerpendicularBlockedByNeighbourBuffer_picksAnotherEdge() {
        Polygon own = square(0, 0, 100, 100);
        Polygon neighbour = square(-20, 0, -10, 100);
        NetworkDataset dataset = dataset(
                List.of(restriction("own", own), restriction("neighbour", neighbour)),
                point("cp", 10, 50));

        OksApproachResolver.Approach approach = approach(dataset, "cp");

        assertThat(approach.isBlocked()).isFalse();
        assertThat(approach.getTail()).hasSize(2);
        // Ближайшая грань x=0 блокирована буфером соседа — вывод уходит через y=0.
        assertThat(approach.getTarget().y).isLessThan(0.0);
        assertThat(approach.getTarget().x).isCloseTo(10.0, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(distanceTo(approach.getTarget(), own)).isGreaterThanOrEqualTo(OFFSET);
        assertThat(distanceTo(approach.getTarget(), neighbour)).isGreaterThanOrEqualTo(OFFSET);
    }

    @Test
    void resolve_pointNearCorner_usesEdgeProjectionNotVertex() {
        Polygon own = square(0, 0, 100, 100);
        NetworkDataset dataset = dataset(
                List.of(restriction("own", own)),
                point("cp", 98, 98));

        OksApproachResolver.Approach approach = approach(dataset, "cp");

        assertThat(approach.isBlocked()).isFalse();
        assertThat(approach.getTail()).hasSize(2);
        // Рёбра x=100 и y=100 равноудалены; выбирается первое по порядку.
        assertThat(approach.getTarget().x).isGreaterThan(100.0);
        assertThat(approach.getTarget().y).isCloseTo(98.0, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(distanceTo(approach.getTarget(), own)).isGreaterThanOrEqualTo(OFFSET);
    }

    @Test
    void resolve_pointOutsideOks_hasNoTail() {
        Polygon own = square(0, 0, 100, 100);
        NetworkDataset dataset = dataset(
                List.of(restriction("own", own)),
                point("cp", 200, 200));

        OksApproachResolver.Approach approach = approach(dataset, "cp");

        assertThat(approach.isBlocked()).isFalse();
        assertThat(approach.getTail()).isEmpty();
        assertThat(approach.getTarget().x).isEqualTo(200.0);
    }

    @Test
    void resolveCandidates_orderedByTailLength() {
        Polygon own = square(0, 0, 100, 100);
        NetworkDataset dataset = dataset(
                List.of(restriction("own", own)),
                point("cp", 50, 10));

        Map<String, List<OksApproachResolver.Approach>> lists =
                resolver.resolveCandidates(dataset, DN);
        List<OksApproachResolver.Approach> candidates = lists.get("cp");

        assertThat(candidates).hasSizeGreaterThan(1);
        double previous = -1.0;
        for (OksApproachResolver.Approach candidate : candidates) {
            double length = candidate.getTarget().distance(new Coordinate(50, 10));
            assertThat(length).isGreaterThanOrEqualTo(previous);
            previous = length;
        }
        // Кратчайший хвост — через ближнюю грань y=0.
        assertThat(candidates.get(0).getTarget().y).isLessThan(0.0);
    }

    @Test
    void resolveExits_pointInSquare_returnsPerpendicularExitWithPerPointDiameter() {
        Polygon own = square(0, 0, 100, 100);
        NetworkDataset dataset = dataset(List.of(restriction("own", own)), point("cp", 10, 50));

        ConnectionExit exit = resolver.resolveExits(dataset).get("cp");

        assertThat(exit.isBlocked()).isFalse();
        assertThat(exit.getDesignDiameterMm()).isEqualTo(DN);
        assertThat(exit.hasTail()).isTrue();
        assertThat(exit.getTarget().x)
                .isCloseTo(-OFFSET, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(exit.getTarget().y)
                .isCloseTo(50.0, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(distanceTo(exit.getTarget(), own)).isGreaterThanOrEqualTo(OFFSET);
    }

    @Test
    void resolveExits_diameterFollowsPointFlow() {
        Polygon own = square(0, 0, 100, 100);
        // flow 3.0 → Ду 50 (пропускная 3.5), полуширина 0.2 → offset 5.2.
        NetworkDataset dataset = dataset(List.of(restriction("own", own)),
                point("cp", 10, 50, 3.0));

        ConnectionExit exit = resolver.resolveExits(dataset).get("cp");

        assertThat(exit.getDesignDiameterMm()).isEqualTo(50);
        assertThat(exit.getTarget().x)
                .isCloseTo(-5.2, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void resolveExits_pointOutsideOks_targetIsPointWithoutTail() {
        Polygon own = square(0, 0, 100, 100);
        NetworkDataset dataset = dataset(List.of(restriction("own", own)), point("cp", 200, 200));

        ConnectionExit exit = resolver.resolveExits(dataset).get("cp");

        assertThat(exit.isBlocked()).isFalse();
        assertThat(exit.hasTail()).isFalse();
        assertThat(exit.getTarget().x).isEqualTo(200.0);
    }

    @Test
    void resolveExits_pointOnOksBoundary_isOwnedWithCovers() {
        Polygon own = square(0, 0, 100, 100);
        NetworkDataset dataset = dataset(List.of(restriction("own", own)), point("cp", 0, 50));

        ConnectionExit exit = resolver.resolveExits(dataset).get("cp");

        assertThat(exit.isBlocked()).isFalse();
        assertThat(exit.hasTail()).as("точка на границе распознана как своя ОКС").isTrue();
    }

    @Test
    void resolveExits_pointOnOksBoundary_ignoredWithoutCovers() {
        Polygon own = square(0, 0, 100, 100);
        NetworkDataset dataset = dataset(List.of(restriction("own", own)), point("cp", 0, 50));
        AppProperties properties = new AppProperties();
        properties.setOksOwningIncludeBoundary(false);
        OksApproachResolver strict = new OksApproachResolver(
                new RestrictionRuleResolver(rules()),
                new EnvelopeCatalog(tables()), new DiameterCatalog(tables()), properties);

        ConnectionExit exit = strict.resolveExits(dataset).get("cp");

        assertThat(exit.isBlocked()).isFalse();
        assertThat(exit.hasTail()).isFalse();
    }

    @Test
    void resolveExits_exitBlockedByNeighbourBuffer_isBlocked() {
        Polygon own = square(0, 0, 100, 100);
        Polygon neighbour = square(-20, 0, -10, 100);
        NetworkDataset dataset = dataset(
                List.of(restriction("own", own), restriction("neighbour", neighbour)),
                point("cp", 10, 50));

        ConnectionExit exit = resolver.resolveExits(dataset).get("cp");

        assertThat(exit.isBlocked()).isTrue();
        assertThat(exit.hasTail()).isFalse();
    }

    @Test
    void resolveExits_flowAboveCatalog_isBlocked() {
        Polygon own = square(0, 0, 100, 100);
        NetworkDataset dataset = dataset(List.of(restriction("own", own)),
                point("cp", 10, 50, 1000.0));

        ConnectionExit exit = resolver.resolveExits(dataset).get("cp");

        assertThat(exit.isBlocked()).isTrue();
        assertThat(exit.getDesignDiameterMm()).isZero();
    }

    @Test
    void resolveExits_pointWithoutFlow_isSkipped() {
        Polygon own = square(0, 0, 100, 100);
        NetworkDataset dataset = dataset(List.of(restriction("own", own)),
                point("cp", 10, 50, null));

        assertThat(resolver.resolveExits(dataset)).doesNotContainKey("cp");
    }

    private OksApproachResolver.Approach approach(NetworkDataset dataset, String pointId) {
        Map<String, OksApproachResolver.Approach> approaches = resolver.resolve(dataset, DN);
        OksApproachResolver.Approach approach = approaches.get(pointId);
        assertThat(approach).isNotNull();
        return approach;
    }

    private double distanceTo(Coordinate coordinate, Polygon polygon) {
        return DistanceOp.distance(
                GeometrySupport.GEOMETRY_FACTORY.createPoint(coordinate), polygon);
    }

    private NetworkDataset dataset(List<RestrictionObject> restrictions,
                                   OksConnectionPointObject point) {
        return NetworkDataset.builder()
                .restrictions(restrictions)
                .connectionPoints(List.of(point))
                .networkSegments(List.of())
                .heatChambers(List.of())
                .sources(List.of())
                .oksFutures(List.of())
                .oksExisting(List.of())
                .build();
    }

    private RestrictionObject restriction(String id, Polygon polygon) {
        return RestrictionObject.builder()
                .id(id).restrictionType("oks").geometry(polygon).build();
    }

    private OksConnectionPointObject point(String id, double x, double y) {
        return point(id, x, y, 10.0);
    }

    private OksConnectionPointObject point(String id, double x, double y, Double flow) {
        return OksConnectionPointObject.builder()
                .id(id).flowTph(flow)
                .geometry(GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(x, y)))
                .build();
    }

    private Polygon square(double minX, double minY, double maxX, double maxY) {
        return GeometrySupport.GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(minX, minY), new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY), new Coordinate(minX, maxY),
                new Coordinate(minX, minY)});
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

    private HeatingTablesProperties tables() {
        HeatingTablesProperties properties = new HeatingTablesProperties();
        properties.setEnvelopes(List.of(envelope(DN, 0.510), envelope(50, 0.400)));
        List<DiameterRow> rows = new java.util.ArrayList<>();
        rows.add(diameter(50, 3.5));
        rows.add(diameter(DN, 22.3));
        properties.setDiameters(rows);
        return properties;
    }

    private EnvelopeRow envelope(int dn, double pairWidthM) {
        EnvelopeRow row = new EnvelopeRow();
        row.setDn(dn);
        row.setPairWidthM(pairWidthM);
        row.setHeightM(0.180);
        return row;
    }

    private DiameterRow diameter(int dn, double capacityTph) {
        DiameterRow row = new DiameterRow();
        row.setDn(dn);
        row.setCapacityTph(capacityTph);
        row.setMaxLengthM(10000);
        row.setNewCostPerM(100000);
        return row;
    }
}
