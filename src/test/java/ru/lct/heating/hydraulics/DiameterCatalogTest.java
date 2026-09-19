package ru.lct.heating.hydraulics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiameterCatalogTest {

    private DiameterCatalog catalog;

    @BeforeEach
    void setUp() {
        HeatingTablesProperties properties = new HeatingTablesProperties();
        properties.setDiameters(rows());
        catalog = new DiameterCatalog(properties);
    }

    @Test
    void selectsSmallestDiameterWithEnoughCapacity() {
        assertThat(catalog.select(3.0).getDn()).isEqualTo(50);
        assertThat(catalog.select(3.5).getDn()).isEqualTo(50);
        assertThat(catalog.select(3.6).getDn()).isEqualTo(65);
        assertThat(catalog.select(100.0).getDn()).isEqualTo(200);
        assertThat(catalog.select(488.72).getDn()).isEqualTo(400);
    }

    @Test
    void nextReturnsHigherNomenclature() {
        assertThat(catalog.next(200)).map(DiameterRow::getDn).contains(250);
        assertThat(catalog.next(1400)).isEmpty();
    }

    @Test
    void exposesCostsAndLengths() {
        assertThat(catalog.newCostPerM(200)).isEqualTo(120275L);
        assertThat(catalog.maxLengthM(300)).isEqualTo(1718.0);
    }

    @Test
    void selectFor_considersFlowAndLength() {
        // 200 мм проходит по расходу, но не по длине 2000 м -> 400 мм.
        assertThat(catalog.selectFor(100.0, 2000.0).getDn()).isEqualTo(400);
        assertThat(catalog.selectFor(100.0, 500.0).getDn()).isEqualTo(200);
    }

    @Test
    void selectForAtLeast_doesNotLowerDiameter() {
        assertThat(catalog.selectForAtLeast(250, 100.0, 10.0).getDn()).isEqualTo(250);
    }

    private List<DiameterRow> rows() {
        List<DiameterRow> rows = new ArrayList<>();
        rows.add(row(50, 3.5, 181, 74023));
        rows.add(row(65, 8.3, 245, 78631));
        rows.add(row(200, 152.3, 1042, 120275));
        rows.add(row(250, 274.9, 1379, 135323));
        rows.add(row(300, 437.4, 1718, 150022));
        rows.add(row(400, 943.1, 2477, 190299));
        rows.add(row(150, 65.1, 696, 105507));
        rows.add(row(1400, 22501.9, 11276, 683417));
        return rows;
    }

    private DiameterRow row(int dn, double capacity, double length, long cost) {
        DiameterRow row = new DiameterRow();
        row.setDn(dn);
        row.setCapacityTph(capacity);
        row.setMaxLengthM(length);
        row.setNewCostPerM(cost);
        return row;
    }
}
