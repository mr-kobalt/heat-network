package ru.lct.heating.hydraulics;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Подбор минимального подходящего Ду и доступ к параметрам номенклатуры (ТП 4.1).
 */
@Component
public class DiameterCatalog {

    private final List<DiameterRow> rows;

    public DiameterCatalog(HeatingTablesProperties properties) {
        this.rows = properties.getDiameters().stream()
                .sorted(Comparator.comparingInt(DiameterRow::getDn))
                .collect(Collectors.toList());
        if (rows.isEmpty()) {
            throw new IllegalStateException("Не задана таблица условных диаметров (heating.diameters)");
        }
    }

    public List<DiameterRow> rows() {
        return rows;
    }

    /**
     * Минимальный Ду с пропускной способностью не меньше расхода.
     */
    public DiameterRow select(double flowTph) {
        return rows.stream()
                .filter(row -> row.getCapacityTph() >= flowTph)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Расход " + flowTph + " т/ч превышает пропускную способность максимального Ду"));
    }

    public Optional<DiameterRow> byDn(int dn) {
        return rows.stream().filter(row -> row.getDn() == dn).findFirst();
    }

    public DiameterRow requireByDn(int dn) {
        return byDn(dn).orElseThrow(() -> new IllegalArgumentException("Неизвестный Ду: " + dn));
    }

    public double maxLengthM(int dn) {
        return requireByDn(dn).getMaxLengthM();
    }

    public long newCostPerM(int dn) {
        return requireByDn(dn).getNewCostPerM();
    }

    /**
     * Следующая номенклатура (для правила +1 при превышении предельной длины, протокол).
     */
    public Optional<DiameterRow> next(int dn) {
        return rows.stream().filter(row -> row.getDn() > dn).findFirst();
    }

    /**
     * Минимальный Ду, одновременно удовлетворяющий расходу и предельной длине
     * (ТП v2 §2.3, FR-43).
     */
    public DiameterRow selectFor(double flowTph, double runLengthM) {
        return rows.stream()
                .filter(row -> row.getCapacityTph() >= flowTph)
                .filter(row -> row.getMaxLengthM() >= runLengthM - 1e-9)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Нет Ду, удовлетворяющего расходу " + flowTph
                                + " т/ч и длине " + runLengthM + " м"));
    }

    /** Наименьший Ду не меньше заданного, удовлетворяющий расходу и длине. */
    public DiameterRow selectForAtLeast(int dn, double flowTph, double runLengthM) {
        return rows.stream()
                .filter(row -> row.getDn() >= dn)
                .filter(row -> row.getCapacityTph() >= flowTph)
                .filter(row -> row.getMaxLengthM() >= runLengthM - 1e-9)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Нет Ду ≥ " + dn + " для расхода " + flowTph + " и длины " + runLengthM));
    }
}
