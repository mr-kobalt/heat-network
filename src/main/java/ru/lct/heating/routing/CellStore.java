package ru.lct.heating.routing;

/**
 * Состояние клеток сетки поиска (ADR-0033, фаза B). Тяжёлые поля
 * ({@code dist}/{@code parent}) могут вытесняться в PostGIS (TEMP) при
 * превышении бюджета ОЗУ; флаги — компактные битсеты.
 */
public interface CellStore extends AutoCloseable {

    double dist(int cell);

    void setDist(int cell, double value);

    int parent(int cell);

    void setParent(int cell, int value);

    boolean settled(int cell);

    void setSettled(int cell, boolean value);

    boolean inForest(int cell);

    void setInForest(int cell, boolean value);

    /** Сброс накопленных страниц (например, по завершении прохода). */
    void flush();

    @Override
    void close();
}
