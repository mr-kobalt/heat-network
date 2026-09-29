package ru.lct.heating.routing;

/**
 * E8-15b: разреженная карта {@code клетка → double} с открытой адресацией и
 * значением по умолчанию для отсутствующих ключей. Нужна, чтобы растр спецзон
 * ({@code Kспец}) не занимал {@code double[width*height]} на всю сетку
 * (NFR-08: вход до 3 ГБ). Не потокобезопасна; читается параллельно из
 * Дейкстры после построения (структура не изменяется при чтении).
 */
public final class SparseCellValues {

    private static final int EMPTY = -1;
    private static final double LOAD_FACTOR = 0.5;

    private final double defaultValue;
    private int[] keys;
    private double[] values;
    private int size;
    private int mask;

    public SparseCellValues(int expected, double defaultValue) {
        this.defaultValue = defaultValue;
        int capacity = 16;
        while (capacity < expected / LOAD_FACTOR) {
            capacity <<= 1;
        }
        this.keys = new int[capacity];
        this.values = new double[capacity];
        this.mask = capacity - 1;
        java.util.Arrays.fill(keys, EMPTY);
    }

    public double defaultValue() {
        return defaultValue;
    }

    public int size() {
        return size;
    }

    public double get(int key) {
        int index = slot(key);
        return keys[index] == EMPTY ? defaultValue : values[index];
    }

    public void put(int key, double value) {
        if ((size + 1) * 2 > keys.length) {
            rehash();
        }
        int index = slot(key);
        if (keys[index] == EMPTY) {
            keys[index] = key;
            size++;
        }
        values[index] = value;
    }

    /** Установить значение, если оно больше текущего (для максимума по клеткам). */
    public void mergeMax(int key, double value) {
        int index = slot(key);
        if (keys[index] == EMPTY) {
            if ((size + 1) * 2 > keys.length) {
                rehash();
                index = slot(key);
            }
            keys[index] = key;
            values[index] = Math.max(defaultValue, value);
            size++;
            return;
        }
        values[index] = Math.max(values[index], value);
    }

    private int slot(int key) {
        int index = mix(key) & mask;
        while (keys[index] != EMPTY && keys[index] != key) {
            index = (index + 1) & mask;
        }
        return index;
    }

    private void rehash() {
        int[] oldKeys = keys;
        double[] oldValues = values;
        keys = new int[oldKeys.length << 1];
        values = new double[keys.length];
        java.util.Arrays.fill(keys, EMPTY);
        mask = keys.length - 1;
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    private static int mix(int z) {
        z = (z ^ (z >>> 16)) * 0x7feb352d;
        z = (z ^ (z >>> 15)) * 0x846ca68b;
        return z ^ (z >>> 16);
    }
}
