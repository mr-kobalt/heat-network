package ru.lct.heating.hydraulics;

import lombok.Data;

@Data
public class DiameterRow {
    private int dn;
    private double capacityTph;
    private double maxLengthM;
    private long newCostPerM;
    private long reconstructionCostPerM;
}
