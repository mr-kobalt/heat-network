package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Инварианты обязательной части ТП v2 на реальном наборе (эпик E28): отсутствие
 * самопересечений (FR-29), поворотов &gt;90° (FR-34) и превышения степени камеры
 * (FR-26). Пороговые значения фиксируют текущее состояние и ужесточаются по мере
 * закрытия E25/E26/E27/E23-04.
 */
@Tag("slow")
class GridForestInvariantsSlowTest extends AbstractCalculationPipelineTest {

    private static final Path SAMPLE = Path.of("source", "Датасет скорректированный.geojson");

    private Path runPipeline() throws Exception {
        assumeTrue(Files.exists(SAMPLE),
                "Набор source/Датасет скорректированный.geojson недоступен");
        Path resultFile = tempDir.resolve("invariants-result.geojson");
        Path summaryFile = tempDir.resolve("invariants-summary.json");
        CalculationOutcome outcome = service().calculate(SAMPLE, resultFile, summaryFile);
        assertThat(outcome.getSummary()).isNotNull();
        return resultFile;
    }

    private void printTurnViolations(Path result, ObjectMapper mapper) throws Exception {
        String best = bestVariantId(result, mapper);
        for (OutputEdgeRef edge : outputEdges(result, mapper, best)) {
            org.locationtech.jts.geom.LineString projected = (org.locationtech.jts.geom.LineString)
                    crsTransformer.toUtm(edge.line());
            org.locationtech.jts.geom.Coordinate[] cs = projected.getCoordinates();
            for (int i = 1; i < cs.length - 1; i++) {
                double inX = cs[i].x - cs[i - 1].x;
                double inY = cs[i].y - cs[i - 1].y;
                double outX = cs[i + 1].x - cs[i].x;
                double outY = cs[i + 1].y - cs[i].y;
                double angle = Math.toDegrees(Math.atan2(Math.abs(inX * outY - inY * outX),
                        inX * outX + inY * outY));
                if (angle > 90.0 + 1e-6) {
                    System.out.println("TURN " + edge.id() + " [" + edge.startNode() + "->"
                            + edge.endNode() + "] vertex " + i + " angle=" + Math.round(angle)
                            + " prev=" + cs[i - 1] + " v=" + cs[i] + " next=" + cs[i + 1]);
                }
            }
        }
    }

    @Test
    void reportsInvariantCounts() throws Exception {
        Path resultFile = runPipeline();
        ObjectMapper mapper = new ObjectMapper();
        int selfIntersections = countSelfIntersections(resultFile, mapper);
        int turnViolations = countTurnViolations(resultFile, mapper);
        printTurnViolations(resultFile, mapper);
        int chamberDegreeViolations = countChamberDegreeViolations(resultFile, mapper);
        int exitMismatches = countMissingCanonicalExits(SAMPLE, resultFile, mapper);
        System.out.println("INVARIANTS selfIntersections=" + selfIntersections
                + " turnViolations=" + turnViolations
                + " chamberDegreeViolations=" + chamberDegreeViolations
                + " exitMismatches=" + exitMismatches);
        // FR-29 и FR-34 на текущем наборе выполняются (E23-04/E27 закрыты).
        assertThat(selfIntersections).isZero();
        assertThat(turnViolations).isZero();
        // FR-26: текущие новые камеры в норме; после E26 — учёт существующих примыканий.
        assertThat(chamberDegreeViolations).isZero();
        // E50: канонический выход присутствует в терминальном ребре любого варианта.
        assertThat(exitMismatches).isZero();
        // E50/ТП 2.2: трасса входит в свой ОКС только финальным выводом (все варианты).
        assertNoOksCrossingBeyondApproach(SAMPLE, resultFile, mapper);
    }
}
