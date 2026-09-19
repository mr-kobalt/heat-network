package ru.lct.heating.hydraulics;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Доступ к расчётным габаритам пары труб по Ду (ТП 4.2).
 * Используются при проверке минимальных расстояний (FR-53, FR-56).
 */
@Component
public class EnvelopeCatalog {

    private final List<EnvelopeRow> rows;

    public EnvelopeCatalog(HeatingTablesProperties properties) {
        this.rows = properties.getEnvelopes().stream()
                .sorted(Comparator.comparingInt(EnvelopeRow::getDn))
                .collect(Collectors.toList());
    }

    /**
     * Половина ширины габарита пары труб: буфер препятствия расширяется на неё,
     * чтобы расстояние считалось между внешними границами (протокол).
     */
    public double halfPairWidthM(int dn) {
        return rowForDn(dn).getPairWidthM() / 2.0;
    }

    public double pairWidthM(int dn) {
        return rowForDn(dn).getPairWidthM();
    }

    public double heightM(int dn) {
        return rowForDn(dn).getHeightM();
    }

    /**
     * Ближайший габарит с Ду не меньше заданного; для значений выше максимума —
     * последняя строка. Пустой каталог трактуется как нулевой габарит.
     */
    private EnvelopeRow rowForDn(int dn) {
        if (rows.isEmpty()) {
            EnvelopeRow empty = new EnvelopeRow();
            empty.setDn(dn);
            return empty;
        }
        return rows.stream()
                .filter(row -> row.getDn() >= dn)
                .findFirst()
                .orElse(rows.get(rows.size() - 1));
    }

    public List<EnvelopeRow> rows() {
        return rows;
    }
}
