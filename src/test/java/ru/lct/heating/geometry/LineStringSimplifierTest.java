package ru.lct.heating.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

class LineStringSimplifierTest {

    private final LineStringSimplifier simplifier = new LineStringSimplifier();

    @Test
    void simplify_collinearVertices_removed() {
        List<Coordinate> result = simplifier.simplify(List.of(
                new Coordinate(0, 0), new Coordinate(5, 0), new Coordinate(10, 0)));
        assertThat(result).containsExactly(new Coordinate(0, 0), new Coordinate(10, 0));
    }

    @Test
    void simplify_duplicateVertices_removed() {
        List<Coordinate> result = simplifier.simplify(List.of(
                new Coordinate(0, 0), new Coordinate(0, 0), new Coordinate(10, 0)));
        assertThat(result).containsExactly(new Coordinate(0, 0), new Coordinate(10, 0));
    }

    @Test
    void simplify_realBend_kept() {
        List<Coordinate> result = simplifier.simplify(List.of(
                new Coordinate(0, 0), new Coordinate(10, 0), new Coordinate(10, 10)));
        assertThat(result).hasSize(3);
    }
}
