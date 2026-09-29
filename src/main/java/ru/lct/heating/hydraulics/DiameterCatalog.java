package ru.lct.heating.hydraulics;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Подбор минимального подходящего Ду и доступ к параметрам номенклатуры (ТП 4.1).
 *
 * <p>Для горячих путей (подбор Ду и стоимость на каждом ребре Дейкстры/relink)
 * поиск по номенклатуре — примитивные массивы и индекс {@code dn → row}, без
 * stream-пайплайнов (E8).</p>
 */
@Component
public class DiameterCatalog {

    private final List<DiameterRow> rows;
    private final DiameterRow[] byDnIndex;
    private final int maxDn;
    /** Стоимость нового строительства руб./м по индексу Ду — для горячих циклов. */
    private final double[] costPerMDn;

    public DiameterCatalog(HeatingTablesProperties properties) {
        this.rows = properties.getDiameters().stream()
                .sorted(Comparator.comparingInt(DiameterRow::getDn))
                .collect(Collectors.toList());
        if (rows.isEmpty()) {
            throw new IllegalStateException("Не задана таблица условных диаметров (heating.diameters)");
        }
        this.maxDn = rows.get(rows.size() - 1).getDn();
        this.byDnIndex = new DiameterRow[Math.max(1, maxDn + 1)];
        this.costPerMDn = new double[Math.max(1, maxDn + 1)];
        for (DiameterRow row : rows) {
            if (row.getDn() >= 0 && row.getDn() < byDnIndex.length) {
                byDnIndex[row.getDn()] = row;
                costPerMDn[row.getDn()] = row.getNewCostPerM();
            }
        }
    }

    public List<DiameterRow> rows() {
        return rows;
    }

    /**
     * Минимальный Ду с пропускной способностью не меньше расхода.
     */
    public DiameterRow select(double flowTph) {
        for (DiameterRow row : rows) {
            if (row.getCapacityTph() >= flowTph) {
                return row;
            }
        }
        throw new IllegalArgumentException(
                "Расход " + flowTph + " т/ч превышает пропускную способность максимального Ду");
    }

    public DiameterRow requireByDn(int dn) {
        DiameterRow row = dn >= 0 && dn < byDnIndex.length ? byDnIndex[dn] : null;
        if (row == null) {
            throw new IllegalArgumentException("Неизвестный Ду: " + dn);
        }
        return row;
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
        for (DiameterRow row : rows) {
            if (row.getDn() > dn) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    /**
     * Минимальный Ду, одновременно удовлетворяющий расходу и предельной длине
     * (ТП v2 §2.3, FR-43).
     */
    public DiameterRow selectFor(double flowTph, double runLengthM) {
        for (DiameterRow row : rows) {
            if (row.getCapacityTph() >= flowTph && row.getMaxLengthM() >= runLengthM - 1e-9) {
                return row;
            }
        }
        throw new IllegalArgumentException(
                "Нет Ду, удовлетворяющего расходу " + flowTph + " т/ч и длине " + runLengthM + " м");
    }

    /** Наименьший Ду не меньше заданного, удовлетворяющий расходу и длине. */
    public DiameterRow selectForAtLeast(int dn, double flowTph, double runLengthM) {
        for (DiameterRow row : rows) {
            if (row.getDn() >= dn && row.getCapacityTph() >= flowTph
                    && row.getMaxLengthM() >= runLengthM - 1e-9) {
                return row;
            }
        }
        throw new IllegalArgumentException(
                "Нет Ду ≥ " + dn + " для расхода " + flowTph + " и длины " + runLengthM);
    }

    /**
     * Таблица {@code newCostPerM} по индексу Ду (руб./м). Стоимость нового
     * строительства монотонна по Ду, поэтому в горячем цикле достаточно
     * {@code max} значений таблицы на клетках. Возвращаемый массив не изменять.
     */
    public double[] costPerMDnTable() {
        return costPerMDn;
    }

    @Override
    public String toString() {
        return "DiameterCatalog" + rows;
    }
}
