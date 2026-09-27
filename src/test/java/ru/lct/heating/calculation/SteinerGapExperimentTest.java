package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

/**
 * Шаг D (Steiner): индикативная оценка зазора по длине.
 *
 * <p>Сравнивает текущую длину леса с ориентирами без препятствий:
 * {@code MST(терминалы)} и простой Штейнер-эвристикой (MST по терминалам ∪
 * точки Ферма троек), а также {@code MST(терминалы ∪ камеры)}. Границы
 * <b>индикативны</b>: игнорируют препятствия, Ду-стоимость, камеры/врезки и
 * обязательную привязку к сети.</p>
 *
 * <p>Запуск: {@code mvn test -Dtest=SteinerGapExperimentTest
 * -Dsurefire.excludedGroups= -Dgroups=slow}.</p>
 */
@Tag("slow")
class SteinerGapExperimentTest extends AbstractCalculationPipelineTest {

    private static final Path MAIN = Path.of("source", "Датасет скорректированный.geojson");
    private static final Path OSM = Path.of("source", "Датасет с препятствиями OSM.geojson");
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    @Test
    void reportsSteinerGap() throws Exception {
        assumeTrue(Files.exists(MAIN) && Files.exists(OSM), "наборы недоступны");
        for (Path sample : new Path[] {MAIN, OSM}) {
            run(sample.getFileName().toString(), sample);
        }
    }

    private void run(String label, Path sample) throws Exception {
        JsonNode root = new ObjectMapper().readTree(sample.toFile());
        List<double[]> terminals = collect(root, "oks_connection_point");
        List<double[]> chambers = collect(root, "heat_chamber");

        CalculationOutcome outcome = service().calculate(sample,
                tempDir.resolve(label + ".geojson"), tempDir.resolve(label + "-summary.json"));
        double currentL = outcome.getSummary().getNewNetworkLengthM();
        double currentS = outcome.getSummary().getScore();
        long segments = outcome.getSummary().getCalculatedCost()
                - outcome.getSummary().getChamberConstructionCost()
                - outcome.getSummary().getExistingChamberTieInCost();

        double mstTerm = mst(terminals);
        double[] terminalsAndChambers = concat(terminals, chambers);
        double mstTermCham = mst(terminalsAndChambers);
        double steinerTerm = steinerMst(terminals);

        System.out.println("STEINER_GAP [" + label + "]"
                + " currentL=" + round(currentL)
                + " currentS=" + round(currentS)
                + " costSegments=" + segments
                + " costChambers=" + outcome.getSummary().getChamberConstructionCost()
                + " costTieIns=" + outcome.getSummary().getExistingChamberTieInCost()
                + " terminals=" + terminals.size() + " chambers=" + chambers.size()
                + " mstTerminals=" + round(mstTerm)
                + " steinerTerminals=" + round(steinerTerm)
                + " mstTerminalsChambers=" + round(mstTermCham)
                + " L/steiner=" + round(currentL / steinerTerm)
                + " L/mst=" + round(currentL / mstTerm)
                + " steiner/mst=" + round(steinerTerm / mstTerm));
        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
    }

    /** Геометрия точек (WGS84 во входе) → UTM-метры. */
    private List<double[]> collect(JsonNode root, String objectType) {
        List<double[]> result = new ArrayList<>();
        for (JsonNode feature : root.path("features")) {
            if (!objectType.equals(feature.path("properties").path("object_type").asText())) {
                continue;
            }
            JsonNode coordinates = feature.path("geometry").path("coordinates");
            Coordinate wgs = new Coordinate(coordinates.get(0).asDouble(), coordinates.get(1).asDouble());
            Coordinate utm = crsTransformer.toUtm(GEOMETRY.createPoint(wgs)).getCoordinate();
            result.add(new double[]{utm.x, utm.y});
        }
        return result;
    }

    private double[] concat(List<double[]> first, List<double[]> second) {
        double[][] all = new double[first.size() + second.size()][];
        for (int i = 0; i < first.size(); i++) {
            all[i] = first.get(i);
        }
        for (int i = 0; i < second.size(); i++) {
            all[first.size() + i] = second.get(i);
        }
        return flatten(all);
    }

    private double[] flatten(double[][] points) {
        double[] result = new double[points.length * 2];
        for (int i = 0; i < points.length; i++) {
            result[2 * i] = points[i][0];
            result[2 * i + 1] = points[i][1];
        }
        return result;
    }

    private double mst(List<double[]> points) {
        return mst(flatten(points.toArray(new double[0][])));
    }

    /** Прим по плоскому массиву [x0,y0,x1,y1,...]. */
    private double mst(double[] flat) {
        int n = flat.length / 2;
        if (n <= 1) {
            return 0.0;
        }
        boolean[] used = new boolean[n];
        double[] best = new double[n];
        java.util.Arrays.fill(best, Double.POSITIVE_INFINITY);
        best[0] = 0.0;
        double total = 0.0;
        for (int step = 0; step < n; step++) {
            int u = -1;
            for (int i = 0; i < n; i++) {
                if (!used[i] && (u == -1 || best[i] < best[u])) {
                    u = i;
                }
            }
            used[u] = true;
            total += best[u];
            for (int v = 0; v < n; v++) {
                if (!used[v]) {
                    double d = Math.hypot(flat[2 * u] - flat[2 * v], flat[2 * u + 1] - flat[2 * v + 1]);
                    if (d < best[v]) {
                        best[v] = d;
                    }
                }
            }
        }
        return total;
    }

    /** Штейнер-ориентир: MST по терминалам ∪ точки Ферма всех троек. */
    /**
     * Штейнер-ориентир: итеративный 1-Steiner — в MST терминалов добавляется
     * точка Ферма тройки, если она строго уменьшает длину дерева.
     */
    private double steinerMst(List<double[]> terminals) {
        List<double[]> chosen = new ArrayList<>(terminals);
        double current = mst(chosen);
        List<double[]> candidates = fermatCandidates(terminals);
        for (int step = 0; step < 50; step++) {
            double bestGain = 1e-6;
            double[] bestCandidate = null;
            for (double[] candidate : candidates) {
                List<double[]> trial = new ArrayList<>(chosen);
                trial.add(candidate);
                double gain = current - mst(trial);
                if (gain > bestGain) {
                    bestGain = gain;
                    bestCandidate = candidate;
                }
            }
            if (bestCandidate == null) {
                break;
            }
            chosen.add(bestCandidate);
            current -= bestGain;
            candidates.remove(bestCandidate);
        }
        return current;
    }

    private List<double[]> fermatCandidates(List<double[]> terminals) {
        List<double[]> candidates = new ArrayList<>();
        int n = terminals.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int k = j + 1; k < n; k++) {
                    candidates.add(fermat(terminals.get(i), terminals.get(j), terminals.get(k)));
                }
            }
        }
        return candidates;
    }

    /** Точка Ферма тройки (Вейсфельд/геометрическая медиана; при угле ≥120° — вершина). */
    private double[] fermat(double[] a, double[] b, double[] c) {
        double[] p = {(a[0] + b[0] + c[0]) / 3.0, (a[1] + b[1] + c[1]) / 3.0};
        double[][] pts = {a, b, c};
        for (int iteration = 0; iteration < 60; iteration++) {
            double nx = 0.0;
            double ny = 0.0;
            double den = 0.0;
            for (double[] q : pts) {
                double d = Math.hypot(p[0] - q[0], p[1] - q[1]);
                if (d < 1e-9) {
                    return q;
                }
                double w = 1.0 / d;
                nx += q[0] * w;
                ny += q[1] * w;
                den += w;
            }
            double nextX = nx / den;
            double nextY = ny / den;
            if (Math.hypot(nextX - p[0], nextY - p[1]) < 1e-6) {
                break;
            }
            p[0] = nextX;
            p[1] = nextY;
        }
        return p;
    }

    private String round(double value) {
        return String.format("%.2f", value);
    }
}
