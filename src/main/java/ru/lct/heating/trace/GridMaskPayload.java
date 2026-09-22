package ru.lct.heating.trace;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * Растровая диагностика этапа «сетка» (ADR-0036): маска запретов и достижимых
 * клеток в виде base64-битсетов. Битсет ориентирован по строкам от северной
 * границы (строка 0 — верх изображения), бит {@code i = row * imageWidth + col},
 * порядок битов внутри байта — младший вперёд. Координаты для наложения
 * (границы, источники, клетки входа) — в WGS84.
 */
@Value
@Builder(toBuilder = true)
public class GridMaskPayload {
    double originX;
    double originY;
    double cellM;
    /** Размер исходной сетки поиска. */
    int width;
    int height;
    /** Размер изображения после прореживания (если оно было). */
    int imageWidth;
    int imageHeight;
    double imageCellM;
    boolean downscaled;
    /** base64-битсеты запретов и достижимых клеток (размер imageWidth*imageHeight). */
    String blocked;
    String reachable;
    /** Углы изображения TL, TR, BR, BL в WGS84 [lng, lat] (заполняет писатель). */
    List<double[]> boundsWgs84;
    /**
     * Клетки-источники (кандидаты врезки). Планировщик передаёт центры клеток в
     * EPSG:32637; писатель преобразует их в WGS84 для итогового {@code grid.json}.
     */
    List<double[]> sources;
    /** Клетки входа терминалов (аналогично {@link #sources}). */
    List<double[]> terminalCells;
}
