package ru.lct.heating.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Service;
import ru.lct.heating.domain.HeatChamberObject;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.NetworkSegment;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.domain.RestrictionObject;
import ru.lct.heating.domain.SourceObject;

/**
 * Разбор входного GeoJSON в {@link NetworkDataset} и сбор диагностики (FR-04…FR-08).
 */
@Service
public class IngestService {

    private final GeoJsonStreamReader reader;
    private final FeatureParser featureParser;

    public IngestService(GeoJsonStreamReader reader, FeatureParser featureParser) {
        this.reader = reader;
        this.featureParser = featureParser;
    }

    public IngestResult ingest(InputStream inputStream) throws IOException {
        Accumulator accumulator = new Accumulator();
        reader.read(inputStream, feature -> accumulate(feature, accumulator));
        return accumulator.toResult();
    }

    /** E8-15d2c: ingest партиции в формате JSONL (одна фича на строку). */
    public IngestResult ingestLines(InputStream inputStream) throws IOException {
        Accumulator accumulator = new Accumulator();
        reader.readLines(inputStream, feature -> accumulate(feature, accumulator));
        return accumulator.toResult();
    }

    private void accumulate(JsonNode feature, Accumulator accumulator) {
        accumulator.totalFeatures++;
        Optional<ParsedFeature> parsed = featureParser.parse(feature, accumulator.warnings);
        parsed.ifPresent(accumulator::add);
    }

    private static final class Accumulator {
        private final List<SourceObject> sources = new ArrayList<>();
        private final List<NetworkSegment> networkSegments = new ArrayList<>();
        private final List<HeatChamberObject> heatChambers = new ArrayList<>();
        private final List<OksConnectionPointObject> connectionPoints = new ArrayList<>();
        private final List<RestrictionObject> restrictions = new ArrayList<>();
        private final List<IngestWarning> warnings = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final Map<String, Integer> countsByType = new LinkedHashMap<>();
        private final Envelope bounds = new Envelope();
        private long totalFeatures;

        void add(ParsedFeature parsed) {
            countsByType.merge(parsed.getType().wireName(), 1, Integer::sum);
            switch (parsed.getType()) {
                case SOURCE:
                    sources.add((SourceObject) parsed.getObject());
                    break;
                case HEAT_NETWORK:
                    NetworkSegment segment = (NetworkSegment) parsed.getObject();
                    if (segment.getDiameterMm() == null) {
                        warnings.add(IngestWarning.builder()
                                .code("MISSING_DIAMETER")
                                .objectId(segment.getId())
                                .message("У существующего участка отсутствует diameter")
                                .build());
                    }
                    networkSegments.add(segment);
                    break;
                case HEAT_CHAMBER:
                    heatChambers.add((HeatChamberObject) parsed.getObject());
                    break;
                case OKS_CONNECTION_POINT:
                    connectionPoints.add((OksConnectionPointObject) parsed.getObject());
                    break;
                case RESTRICTION:
                    restrictions.add((RestrictionObject) parsed.getObject());
                    break;
                default:
                    break;
            }
            Geometry geometry = geometryOf(parsed.getObject());
            if (geometry != null && !geometry.isEmpty()) {
                bounds.expandToInclude(geometry.getEnvelopeInternal());
            }
        }

        private static Geometry geometryOf(Object object) {
            if (object instanceof SourceObject) {
                return ((SourceObject) object).getGeometry();
            }
            if (object instanceof NetworkSegment) {
                return ((NetworkSegment) object).getGeometry();
            }
            if (object instanceof HeatChamberObject) {
                return ((HeatChamberObject) object).getGeometry();
            }
            if (object instanceof OksConnectionPointObject) {
                return ((OksConnectionPointObject) object).getGeometry();
            }
            if (object instanceof RestrictionObject) {
                return ((RestrictionObject) object).getGeometry();
            }
            return null;
        }

        IngestResult toResult() {
            if (totalFeatures == 0) {
                errors.add("EMPTY_FEATURE_COLLECTION: входной файл не содержит объектов");
            }
            if (sources.isEmpty()) {
                warnings.add(IngestWarning.builder()
                        .code("NO_SOURCE")
                        .message("Во входных данных отсутствует объект source")
                        .build());
            }
            double[] bbox = bounds.isNull()
                    ? new double[0]
                    : new double[]{bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(), bounds.getMaxY()};
            NetworkDataset dataset = NetworkDataset.builder()
                    .sources(sources)
                    .networkSegments(networkSegments)
                    .heatChambers(heatChambers)
                    .connectionPoints(connectionPoints)
                    .restrictions(restrictions)
                    .bounds(bounds)
                    .build();
            IngestDiagnostics diagnostics = IngestDiagnostics.builder()
                    .totalFeatures(totalFeatures)
                    .countsByType(countsByType)
                    .bbox(bbox)
                    .warnings(warnings)
                    .errors(errors)
                    .build();
            return IngestResult.builder().dataset(dataset).diagnostics(diagnostics).build();
        }
    }
}
