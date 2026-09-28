package ru.lct.heating.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.geometry.ObstacleIndex;

/**
 * E50-07 (спайк): visibility-фолбэк терминального ствола прошивает узкий
 * свободный коридор, который не представляет сетка 1 м. Синтетическая сцена:
 * буфер своего ОКС и буфер здания оставляют полосу 0.5 м; прямой отрезок
 * {@code from→target} пересекает буфер ОКС, а сеточный маршрут вынужден
 * огибать здание. Фолбэк (касание границы допустимо) находит короткий путь
 * вдоль границы буфера.
 */
class ExitVisibilityFallbackTest {

    private static final GeometryFactory GF = GeometrySupport.GEOMETRY_FACTORY;

    @Test
    void threadsNarrowLaneAlongBufferBoundary() {
        AppProperties properties = new AppProperties();
        properties.setForestExitVisibilityFallback(true);
        properties.setForestExitVisibilityMinDetourM(2.0);
        properties.setForestExitVisibilityMaxDetourM(1000.0);
        GridForestPlanner planner = new GridForestPlanner(null, null, null, null, null, null,
                null, properties, null);

        ObstacleIndex index = new ObstacleIndex(List.of(
                PreparedGeometryFactory.prepare(rect(0, 0, 100, 10)),      // буфер ОКС
                PreparedGeometryFactory.prepare(rect(0, 10.5, 100, 30)))); // буфер здания

        Coordinate start = new Coordinate(-5, 5);
        Coordinate startPrevious = new Coordinate(-10, 5);
        Coordinate target = new Coordinate(105, 5);
        Coordinate point = new Coordinate(110, 5);
        // Текущий сеточный ствол огибает здание сверху (дороже прямого).
        List<Coordinate> coords = List.of(start, new Coordinate(-5, 35),
                new Coordinate(105, 35), target, point);

        double laneM = 0.5;
        assertThat(laneM).as("коридор уже ячейки сетки").isLessThan(1.0);
        assertThat(index.isInteriorBlocked(GF.createLineString(
                new Coordinate[]{start, target})))
                .as("прямой отрезок задевает внутреннюю часть буфера ОКС").isTrue();

        List<Coordinate> result = planner.visibilityExitFallback(
                new ArrayList<>(coords), startPrevious, point, index);

        assertThat(result).as("фолбэк найден").isNotNull();
        assertThat(result.get(result.size() - 1)).as("хвост target→point сохранён")
                .isEqualTo(point);
        assertThat(result.get(result.size() - 2)).as("target сохранён").isEqualTo(target);

        double before = length(coords.subList(0, coords.size() - 1));
        double after = length(result.subList(0, result.size() - 1));
        assertThat(after).as("фолбэк короче сеточного ствола").isLessThan(before - 0.5);

        for (int i = 0; i + 1 < result.size(); i++) {
            assertThat(index.isInteriorBlocked(GF.createLineString(
                    new Coordinate[]{result.get(i), result.get(i + 1)})))
                    .as("сегмент %s→%s не входит во внутреннюю часть запрета", i, i + 1)
                    .isFalse();
        }
        for (int i = 1; i + 1 < result.size(); i++) {
            assertThat(turn(result.get(i - 1), result.get(i), result.get(i + 1)))
                    .as("поворот в вершине %s ≤90°", i).isLessThanOrEqualTo(90.0 + 1e-6);
        }
    }

    @Test
    void enabledByDefaultAndCanBeDisabled() {
        AppProperties properties = new AppProperties();
        assertThat(properties.isForestExitVisibilityFallback()).isTrue();
        GridForestPlanner planner = new GridForestPlanner(null, null, null, null, null, null,
                null, properties, null);
        ObstacleIndex index = new ObstacleIndex(List.of(
                PreparedGeometryFactory.prepare(rect(0, 0, 100, 10))));
        List<Coordinate> coords = List.of(new Coordinate(-5, 5), new Coordinate(-5, 35),
                new Coordinate(105, 35), new Coordinate(105, 5), new Coordinate(110, 5));
        properties.setForestExitVisibilityMaxDetourM(1000.0);
        assertThat(planner.visibilityExitFallback(new ArrayList<>(coords), null,
                new Coordinate(110, 5), index)).as("включён по умолчанию").isNotNull();

        properties.setForestExitVisibilityFallback(false);
        assertThat(planner.visibilityExitFallback(new ArrayList<>(coords), null,
                new Coordinate(110, 5), index)).as("отключается").isNull();
    }

    @Test
    void detourOutsideWindowIsSkipped() {
        AppProperties properties = new AppProperties();
        properties.setForestExitVisibilityFallback(true);
        properties.setForestExitVisibilityMaxDetourM(1.0);
        GridForestPlanner planner = new GridForestPlanner(null, null, null, null, null, null,
                null, properties, null);
        ObstacleIndex index = new ObstacleIndex(List.of(
                PreparedGeometryFactory.prepare(rect(0, 0, 100, 10))));
        // Перепробег 60 м больше окна (1 м) — попытка не делается.
        List<Coordinate> coords = List.of(new Coordinate(-5, 5), new Coordinate(-5, 35),
                new Coordinate(105, 35), new Coordinate(105, 5), new Coordinate(110, 5));
        assertThat(planner.visibilityExitFallback(new ArrayList<>(coords), null,
                new Coordinate(110, 5), index)).isNull();
    }

    private Polygon rect(double minX, double minY, double maxX, double maxY) {
        return GF.createPolygon(new Coordinate[]{
                new Coordinate(minX, minY), new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY), new Coordinate(minX, maxY),
                new Coordinate(minX, minY)});
    }

    private double length(List<Coordinate> coordinates) {
        double total = 0.0;
        for (int i = 0; i + 1 < coordinates.size(); i++) {
            total += coordinates.get(i).distance(coordinates.get(i + 1));
        }
        return total;
    }

    private double turn(Coordinate previous, Coordinate vertex, Coordinate next) {
        double inX = vertex.x - previous.x;
        double inY = vertex.y - previous.y;
        double outX = next.x - vertex.x;
        double outY = next.y - vertex.y;
        return Math.toDegrees(Math.atan2(Math.abs(inX * outY - inY * outX),
                inX * outX + inY * outY));
    }
}
