package ru.lct.heating.hydraulics;

import lombok.Data;

/**
 * Расчётные габариты пары труб по Ду (ТП 4.2). Единый источник — конфигурация.
 */
@Data
public class EnvelopeRow {
    private int dn;
    private double outerDiameterM;
    private double clearanceM;
    private double pairWidthM;
    private double heightM;
}
