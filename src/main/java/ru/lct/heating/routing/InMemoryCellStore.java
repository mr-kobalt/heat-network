package ru.lct.heating.routing;

import java.util.Arrays;

/**
 * Состояние клеток в памяти (ADR-0033): {@code dist}/{@code parent} — массивы,
 * флаги — битсеты.
 */
public final class InMemoryCellStore implements CellStore {

    private final double[] dist;
    private final int[] parent;
    private final long[] settled;
    private final long[] inForest;

    public InMemoryCellStore(int cells) {
        this.dist = new double[cells];
        this.parent = new int[cells];
        this.settled = new long[(cells + 63) / 64];
        this.inForest = new long[(cells + 63) / 64];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(parent, -1);
    }

    @Override
    public double dist(int cell) {
        return dist[cell];
    }

    @Override
    public void setDist(int cell, double value) {
        dist[cell] = value;
    }

    @Override
    public int parent(int cell) {
        return parent[cell];
    }

    @Override
    public void setParent(int cell, int value) {
        parent[cell] = value;
    }

    @Override
    public boolean settled(int cell) {
        return (settled[cell >>> 6] & (1L << (cell & 63))) != 0;
    }

    @Override
    public void setSettled(int cell, boolean value) {
        if (value) {
            settled[cell >>> 6] |= 1L << (cell & 63);
        } else {
            settled[cell >>> 6] &= ~(1L << (cell & 63));
        }
    }

    @Override
    public boolean inForest(int cell) {
        return (inForest[cell >>> 6] & (1L << (cell & 63))) != 0;
    }

    @Override
    public void setInForest(int cell, boolean value) {
        if (value) {
            inForest[cell >>> 6] |= 1L << (cell & 63);
        } else {
            inForest[cell >>> 6] &= ~(1L << (cell & 63));
        }
    }

    @Override
    public void flush() {
        // нет вытеснения
    }

    @Override
    public void close() {
        // нет ресурсов
    }
}
