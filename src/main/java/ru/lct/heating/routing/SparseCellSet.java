package ru.lct.heating.routing;

import java.util.Arrays;

/**
 * E8-15b: разреженное множество клеток (открытая адресация) — заменяет плотную
 * битовую маску угловых спецзон и множество достижимых клеток, чтобы не
 * занимать {@code long[width*height]} на всю сетку (NFR-08).
 */
public final class SparseCellSet {

    private static final int EMPTY = -1;

    private int[] keys;
    private int size;
    private int mask;

    public SparseCellSet() {
        this(16);
    }

    public SparseCellSet(int expected) {
        int capacity = 16;
        while (capacity < expected * 2) {
            capacity <<= 1;
        }
        this.keys = new int[capacity];
        this.mask = capacity - 1;
        Arrays.fill(keys, EMPTY);
    }

    public int size() {
        return size;
    }

    public boolean contains(int key) {
        int index = slot(key);
        return keys[index] == key;
    }

    public boolean add(int key) {
        if ((size + 1) * 2 > keys.length) {
            rehash();
        }
        int index = slot(key);
        if (keys[index] == key) {
            return false;
        }
        keys[index] = key;
        size++;
        return true;
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
        keys = new int[oldKeys.length << 1];
        mask = keys.length - 1;
        Arrays.fill(keys, EMPTY);
        size = 0;
        for (int key : oldKeys) {
            if (key != EMPTY) {
                add(key);
            }
        }
    }

    private static int mix(int z) {
        z = (z ^ (z >>> 16)) * 0x7feb352d;
        z = (z ^ (z >>> 15)) * 0x846ca68b;
        return z ^ (z >>> 16);
    }
}
