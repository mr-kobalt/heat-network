package ru.lct.heating.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.cost.CostProperties;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.HeatingTablesProperties;
import ru.lct.heating.hydraulics.MaxLengthEnforcer;

/**
 * ADR-0062: глобальный подбор Ду минимизирует стоимость при соблюдении
 * пропускной способности, невозрастания к корню и предельной длины плети, и
 * не хуже {@link MaxLengthEnforcer}.
 */
class DiameterTreeOptimizerTest {

    private final DiameterCatalog catalog = catalog(
            row(50, 100, 100, 10),
            row(100, 1000, 250, 20),
            row(150, 5000, 1000, 30));
    private final CostModel costModel = new CostModel(catalog, new CostProperties());
    private final DiameterTreeOptimizer optimizer =
            new DiameterTreeOptimizer(catalog, costModel, new AppProperties());
    private final MaxLengthEnforcer enforcer = new MaxLengthEnforcer(catalog);

    @Test
    void sharedTrunkIsRaisedPartiallyInsteadOfWholeRun() {
        Map<String, ForestNode> nodes = nodes(
                node("root", NodeType.CHAMBER, false),
                node("a", NodeType.TECHNICAL_NODE, false),
                node("t", NodeType.CONNECTION_POINT, false));
        List<ForestEdge> edges = List.of(
                edge("e1", "root", "a", 200, 5),
                edge("e2", "a", "t", 100, 5));

        List<ForestEdge> optimized = optimizer.optimize(nodes, edges, "root", null);

        assertThat(segmentCost(optimized)).isLessThan(segmentCost(enforcer.enforce(edges, "root")));
        assertThat(dn(optimized, "e1")).isEqualTo(100);
        assertThat(dn(optimized, "e2")).isEqualTo(50);
    }

    @Test
    void capacityAndNonDecreasingAndRunLengthHold() {
        Map<String, ForestNode> nodes = nodes(
                node("root", NodeType.CHAMBER, false),
                node("a", NodeType.CHAMBER, false),
                node("t1", NodeType.CONNECTION_POINT, false),
                node("t2", NodeType.CONNECTION_POINT, false));
        // Ствол с большим расходом (базовый Ду выше), две короткие ветки.
        List<ForestEdge> edges = List.of(
                edge("e1", "root", "a", 150, 900),
                edge("e2", "a", "t1", 80, 500),
                edge("e3", "a", "t2", 80, 5));

        List<ForestEdge> optimized = optimizer.optimize(nodes, edges, "root", null);

        assertThat(dn(optimized, "e1")).isGreaterThanOrEqualTo(100);
        assertThat(dn(optimized, "e1")).isGreaterThanOrEqualTo(dn(optimized, "e2"));
        assertThat(dn(optimized, "e1")).isGreaterThanOrEqualTo(dn(optimized, "e3"));
        assertThat(dn(optimized, "e2")).isGreaterThanOrEqualTo(100);
        assertThat(dn(optimized, "e3")).isGreaterThanOrEqualTo(50);
        assertThat(segmentCost(optimized))
                .isLessThanOrEqualTo(segmentCost(enforcer.enforce(edges, "root")));
    }

    @Test
    void infeasibleLengthThrows() {
        Map<String, ForestNode> nodes = nodes(
                node("root", NodeType.CHAMBER, false),
                node("t", NodeType.CONNECTION_POINT, false));
        List<ForestEdge> edges = List.of(edge("e1", "root", "t", 5000, 5));
        assertThatThrownBy(() -> optimizer.optimize(nodes, edges, "root", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void singleEdgeWithinLimitIsMinimal() {
        Map<String, ForestNode> nodes = nodes(
                node("root", NodeType.CHAMBER, false),
                node("t", NodeType.CONNECTION_POINT, false));
        List<ForestEdge> edges = List.of(edge("e1", "root", "t", 80, 5));
        List<ForestEdge> optimized = optimizer.optimize(nodes, edges, "root", null);
        assertThat(dn(optimized, "e1")).isEqualTo(50);
    }

    private long segmentCost(List<ForestEdge> edges) {
        long total = 0L;
        for (ForestEdge edge : edges) {
            total += costModel.segmentCost(edge.lengthM(), edge.getDiameterMm(), 1.0, 1.0);
        }
        return total;
    }

    private int dn(List<ForestEdge> edges, String id) {
        return edges.stream().filter(e -> e.getId().equals(id)).findFirst().orElseThrow()
                .getDiameterMm();
    }

    private Map<String, ForestNode> nodes(ForestNode... nodes) {
        Map<String, ForestNode> result = new LinkedHashMap<>();
        for (ForestNode node : nodes) {
            result.put(node.getId(), node);
        }
        return result;
    }

    private ForestNode node(String id, NodeType type, boolean existing) {
        return ForestNode.builder().id(id).type(type)
                .coordinate(new Coordinate(0, 0)).existing(existing).build();
    }

    private ForestEdge edge(String id, String from, String to, double length, double flow) {
        return ForestEdge.builder()
                .id(id)
                .fromNodeId(from)
                .toNodeId(to)
                .coordinates(List.of(new Coordinate(0, 0), new Coordinate(length, 0)))
                .flowTph(flow)
                .diameterMm(catalog.select(flow).getDn())
                .build();
    }

    private DiameterCatalog catalog(DiameterRow... rows) {
        HeatingTablesProperties properties = new HeatingTablesProperties();
        properties.setDiameters(new ArrayList<>(List.of(rows)));
        return new DiameterCatalog(properties);
    }

    private DiameterRow row(int dn, double capacity, double maxLength, long costPerM) {
        DiameterRow row = new DiameterRow();
        row.setDn(dn);
        row.setCapacityTph(capacity);
        row.setMaxLengthM(maxLength);
        row.setNewCostPerM(costPerM);
        return row;
    }
}
