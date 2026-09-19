package ru.lct.heating.hydraulics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EnvelopeCatalogTest {

    @Test
    void halfPairWidthM_exactDiameter_returnsHalfWidth() {
        EnvelopeCatalog catalog = catalog(row(100, 0.510), row(200, 0.880));
        assertThat(catalog.halfPairWidthM(100)).isEqualTo(0.255);
    }

    @Test
    void halfPairWidthM_betweenRows_usesCeilingRow() {
        EnvelopeCatalog catalog = catalog(row(100, 0.510), row(200, 0.880));
        assertThat(catalog.halfPairWidthM(150)).isEqualTo(0.440);
    }

    @Test
    void halfPairWidthM_aboveMaximum_usesLastRow() {
        EnvelopeCatalog catalog = catalog(row(100, 0.510), row(200, 0.880));
        assertThat(catalog.halfPairWidthM(2000)).isEqualTo(0.440);
    }

    @Test
    void halfPairWidthM_emptyCatalog_zero() {
        assertThat(new EnvelopeCatalog(new HeatingTablesProperties()).halfPairWidthM(100)).isZero();
    }

    private EnvelopeCatalog catalog(EnvelopeRow... rows) {
        HeatingTablesProperties properties = new HeatingTablesProperties();
        properties.setEnvelopes(List.of(rows));
        return new EnvelopeCatalog(properties);
    }

    private EnvelopeRow row(int dn, double pairWidth) {
        EnvelopeRow row = new EnvelopeRow();
        row.setDn(dn);
        row.setPairWidthM(pairWidth);
        return row;
    }
}
