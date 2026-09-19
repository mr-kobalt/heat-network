package ru.lct.heating.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.HeatingTablesProperties;

class CostModelTest {

    private CostModel costModel;

    @BeforeEach
    void setUp() {
        HeatingTablesProperties properties = new HeatingTablesProperties();
        List<DiameterRow> rows = new ArrayList<>();
        DiameterRow row = new DiameterRow();
        row.setDn(200);
        row.setCapacityTph(152.3);
        row.setMaxLengthM(1042);
        row.setNewCostPerM(120275);
        rows.add(row);
        properties.setDiameters(rows);
        costModel = new CostModel(new DiameterCatalog(properties), new CostProperties());
    }

    @Test
    void segmentCostUsesLengthDiameterAndCoefficients() {
        long cost = costModel.segmentCost(100.0, 200, 1.0, 1.0);
        assertThat(cost).isEqualTo(12027500L);
        long withSpecial = costModel.segmentCost(100.0, 200, 1.0, 1.6);
        assertThat(withSpecial).isEqualTo(19244000L);
    }

    @Test
    void chamberCostFollowsScale() {
        assertThat(costModel.chamberCost(150)).isEqualTo(3_000_000L);
        assertThat(costModel.chamberCost(400)).isEqualTo(5_000_000L);
        assertThat(costModel.chamberCost(800)).isEqualTo(8_000_000L);
        assertThat(costModel.chamberCost(1400)).isEqualTo(12_000_000L);
    }

    @Test
    void penaltyAndScore() {
        assertThat(costModel.unconnectedPenalty(20.0)).isEqualTo(110_000_000L);
        // 1000 руб. * 0 + 0.3 * (100 / 100) = 0.3
        assertThat(costModel.score(0L, 100.0)).isEqualTo(0.3, org.assertj.core.data.Offset.offset(1e-9));
    }
}
