package ru.lct.heating.hydraulics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heating.routing.ForestEdge;

class MaxLengthEnforcerTest {

    @Test
    void enforce_pathExceedsLimit_bumpsDiameter() {
        MaxLengthEnforcer enforcer = enforcer(row(50, 100), row(100, 150), row(200, 10000));
        List<ForestEdge> edges = List.of(
                edge("e_connection", "C", "A", 80, 50, 10),
                edge("e1", "A", "B", 80, 50, 10));
        List<ForestEdge> result = enforcer.enforce(edges);
        assertThat(result).allMatch(edge -> edge.getDiameterMm() == 200);
    }

    @Test
    void enforce_parallelBranchesNotSummed_noBump() {
        MaxLengthEnforcer enforcer = enforcer(row(50, 1000));
        List<ForestEdge> edges = List.of(
                edge("e_connection", "C", "A", 100, 50, 10),
                edge("e1", "A", "B1", 300, 50, 10),
                edge("e2", "A", "B2", 300, 50, 10));
        List<ForestEdge> result = enforcer.enforce(edges);
        assertThat(result).allMatch(edge -> edge.getDiameterMm() == 50);
    }

    @Test
    void enforce_lengthWithinLimit_noBump() {
        MaxLengthEnforcer enforcer = enforcer(row(50, 1000));
        List<ForestEdge> result = enforcer.enforce(List.of(
                edge("e_connection", "C", "A", 100, 50, 10),
                edge("e1", "A", "B", 100, 50, 10)));
        assertThat(result).allMatch(edge -> edge.getDiameterMm() == 50);
    }

    @Test
    void enforce_diameterNonDecreasingTowardConnection() {
        MaxLengthEnforcer enforcer = enforcer(row(50, 10000), row(100, 10000), row(200, 10000));
        List<ForestEdge> edges = List.of(
                edge("e_connection", "C", "A", 10, 100, 100),
                edge("e1", "A", "B", 10, 200, 10));
        List<ForestEdge> result = enforcer.enforce(edges);
        ForestEdge connection = findById(result, "e_connection");
        assertThat(connection.getDiameterMm()).isGreaterThanOrEqualTo(200);
    }

    private ForestEdge findById(List<ForestEdge> edges, String id) {
        return edges.stream().filter(edge -> edge.getId().equals(id)).findFirst().orElseThrow();
    }

    private MaxLengthEnforcer enforcer(DiameterRow... rows) {
        HeatingTablesProperties properties = new HeatingTablesProperties();
        properties.setDiameters(List.of(rows));
        return new MaxLengthEnforcer(new DiameterCatalog(properties));
    }

    private DiameterRow row(int dn, double maxLength) {
        DiameterRow row = new DiameterRow();
        row.setDn(dn);
        row.setCapacityTph(100000);
        row.setMaxLengthM(maxLength);
        row.setNewCostPerM(1000);
        return row;
    }

    private ForestEdge edge(String id, String from, String to, double length, int dn, double flow) {
        return ForestEdge.builder()
                .id(id)
                .fromNodeId(from)
                .toNodeId(to)
                .coordinates(List.of(new Coordinate(0, 0), new Coordinate(length, 0)))
                .flowTph(flow)
                .diameterMm(dn)
                .build();
    }
}
