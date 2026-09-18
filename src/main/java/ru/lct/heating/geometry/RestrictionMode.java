package ru.lct.heating.geometry;

public enum RestrictionMode {
    /** Пересечение запрещено — объект обходится с минимальным расстоянием. */
    PROHIBITED,
    /** Допускается специальный проход с коэффициентом Kспец. */
    SPECIAL
}
