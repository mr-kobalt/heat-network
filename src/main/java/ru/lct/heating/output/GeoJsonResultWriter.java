package ru.lct.heating.output;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;

/**
 * Потоковая запись результата в GeoJSON (ТП 10, ADR-0008).
 */
@Component
public class GeoJsonResultWriter {

    private final ObjectMapper objectMapper;

    public GeoJsonResultWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(VariantResult variant, OutputStream outputStream) throws IOException {
        try (JsonGenerator generator = objectMapper.getFactory().createGenerator(outputStream)) {
            generator.writeStartObject();
            generator.writeStringField("type", "FeatureCollection");
            generator.writeArrayFieldStart("features");
            for (OutputSegment segment : variant.getSegments()) {
                writeSegment(generator, variant.getVariantId(), segment);
            }
            for (OutputTieIn tieIn : variant.getTieIns()) {
                writeTieIn(generator, variant.getVariantId(), tieIn);
            }
            for (OutputChamber chamber : variant.getChambers()) {
                writeChamber(generator, variant.getVariantId(), chamber);
            }
            for (OutputTechnicalNode node : variant.getTechnicalNodes()) {
                writeTechnicalNode(generator, variant.getVariantId(), node);
            }
            writeSummary(generator, variant.getSummary());
            generator.writeEndArray();
            generator.writeEndObject();
            generator.flush();
        }
    }

    private void writeSegment(JsonGenerator generator, String variantId, OutputSegment segment)
            throws IOException {
        generator.writeStartObject();
        generator.writeStringField("type", "Feature");
        generator.writeFieldName("geometry");
        writeLineString(generator, segment.getGeometryWgs84());
        generator.writeObjectFieldStart("properties");
        generator.writeStringField("id", segment.getId());
        generator.writeStringField("object_type", "heat_network");
        generator.writeStringField("variant_id", variantId);
        generator.writeStringField("start_node_id", segment.getStartNodeId());
        generator.writeStringField("end_node_id", segment.getEndNodeId());
        generator.writeNumberField("flow_tph", segment.getFlowTph());
        generator.writeNumberField("diameter", segment.getDiameterMm());
        generator.writeNumberField("length", segment.getLengthM());
        generator.writeStringField("laying_method", segment.getLayingMethod());
        writeNullableNumber(generator, "depth_start", segment.getDepthStart());
        writeNullableNumber(generator, "depth_end", segment.getDepthEnd());
        generator.writeNumberField("cost", segment.getCost());
        generator.writeEndObject();
        generator.writeEndObject();
    }

    private void writeTieIn(JsonGenerator generator, String variantId, OutputTieIn tieIn)
            throws IOException {
        generator.writeStartObject();
        generator.writeStringField("type", "Feature");
        generator.writeFieldName("geometry");
        writePoint(generator, tieIn.getGeometryWgs84());
        generator.writeObjectFieldStart("properties");
        generator.writeStringField("id", tieIn.getId());
        generator.writeStringField("object_type", "tie_in");
        generator.writeStringField("variant_id", variantId);
        generator.writeStringField("existing_object_id", tieIn.getExistingObjectId());
        generator.writeStringField("existing_object_type", tieIn.getExistingObjectType());
        writeNullableNumber(generator, "existing_diameter", tieIn.getExistingDiameterMm());
        generator.writeNumberField("required_diameter", tieIn.getRequiredDiameterMm());
        generator.writeNumberField("cost", tieIn.getCost());
        generator.writeEndObject();
        generator.writeEndObject();
    }

    private void writeChamber(JsonGenerator generator, String variantId, OutputChamber chamber)
            throws IOException {
        generator.writeStartObject();
        generator.writeStringField("type", "Feature");
        generator.writeFieldName("geometry");
        writePoint(generator, chamber.getGeometryWgs84());
        generator.writeObjectFieldStart("properties");
        generator.writeStringField("id", chamber.getId());
        generator.writeStringField("object_type", "heat_chamber");
        generator.writeStringField("variant_id", variantId);
        generator.writeNumberField("diameter", chamber.getDiameterMm());
        generator.writeNumberField("cost", chamber.getCost());
        generator.writeEndObject();
        generator.writeEndObject();
    }

    private void writeTechnicalNode(JsonGenerator generator, String variantId, OutputTechnicalNode node)
            throws IOException {
        generator.writeStartObject();
        generator.writeStringField("type", "Feature");
        generator.writeFieldName("geometry");
        writePoint(generator, node.getGeometryWgs84());
        generator.writeObjectFieldStart("properties");
        generator.writeStringField("id", node.getId());
        generator.writeStringField("object_type", "technical_node");
        generator.writeStringField("variant_id", variantId);
        generator.writeEndObject();
        generator.writeEndObject();
    }

    private void writeSummary(JsonGenerator generator, VariantSummary summary) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("type", "Feature");
        generator.writeNullField("geometry");
        generator.writeObjectFieldStart("properties");
        generator.writeStringField("id", "summary_" + summary.getVariantId());
        generator.writeStringField("object_type", "variant_summary");
        generator.writeStringField("variant_id", summary.getVariantId());
        generator.writeNumberField("rank", summary.getRank());
        generator.writeNumberField("construction_cost", summary.getConstructionCost());
        generator.writeNumberField("chamber_construction_cost", summary.getChamberConstructionCost());
        generator.writeNumberField("tie_in_cost", summary.getTieInCost());
        generator.writeNumberField("reconstruction_cost", summary.getReconstructionCost());
        generator.writeNumberField("chamber_reconstruction_cost", summary.getChamberReconstructionCost());
        generator.writeNumberField("unconnected_penalty", summary.getUnconnectedPenalty());
        generator.writeNumberField("calculated_cost", summary.getCalculatedCost());
        generator.writeNumberField("new_network_length", summary.getNewNetworkLengthM());
        generator.writeNumberField("reconstruction_length", summary.getReconstructionLengthM());
        generator.writeNumberField("length", summary.getLengthM());
        generator.writeNumberField("score", summary.getScore());
        generator.writeArrayFieldStart("unconnected_oks_ids");
        for (String id : summary.getUnconnectedOksIds()) {
            generator.writeString(id);
        }
        generator.writeEndArray();
        generator.writeEndObject();
        generator.writeEndObject();
    }

    private void writeLineString(JsonGenerator generator, LineString lineString) throws IOException {
        if (lineString == null) {
            generator.writeNull();
            return;
        }
        generator.writeStartObject();
        generator.writeStringField("type", "LineString");
        generator.writeArrayFieldStart("coordinates");
        for (Coordinate coordinate : lineString.getCoordinates()) {
            writeCoordinate(generator, coordinate);
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    private void writePoint(JsonGenerator generator, Point point) throws IOException {
        if (point == null) {
            generator.writeNull();
            return;
        }
        generator.writeStartObject();
        generator.writeStringField("type", "Point");
        generator.writeFieldName("coordinates");
        writeCoordinate(generator, point.getCoordinate());
        generator.writeEndObject();
    }

    private void writeCoordinate(JsonGenerator generator, Coordinate coordinate) throws IOException {
        generator.writeStartArray();
        generator.writeNumber(coordinate.x);
        generator.writeNumber(coordinate.y);
        generator.writeEndArray();
    }

    private void writeNullableNumber(JsonGenerator generator, String field, Number value)
            throws IOException {
        if (value == null) {
            generator.writeNullField(field);
        } else {
            generator.writeNumberField(field, value.doubleValue());
        }
    }
}
