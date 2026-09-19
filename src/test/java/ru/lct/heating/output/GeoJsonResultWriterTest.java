package ru.lct.heating.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GeoJsonResultWriterTest {

    @Test
    void write_multipleVariants_allSummariesPresent() throws Exception {
        GeoJsonResultWriter writer = new GeoJsonResultWriter(new ObjectMapper());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(List.of(variant("v1", 1), variant("v2", 2)), output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json).contains("\"variant_id\":\"v1\"");
        assertThat(json).contains("\"variant_id\":\"v2\"");
        assertThat(json).contains("\"object_type\":\"variant_summary\"");
        assertThat(json).contains("\"existing_chamber_tie_in_count\":1");
        assertThat(json).doesNotContain("tie_in\"");
        assertThat(json).doesNotContain("reconstruction");
    }

    @Test
    void write_numericUnconnectedIds_writtenAsNumbers() throws Exception {
        GeoJsonResultWriter writer = new GeoJsonResultWriter(new ObjectMapper());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        VariantSummary summary = summary("v1", 1).toBuilder()
                .unconnectedOksIds(List.of("7", "x"))
                .numericOksIds(Set.of("7"))
                .build();
        VariantResult variant = variant("v1", 1).toBuilder().summary(summary).build();

        writer.write(List.of(variant), output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json).contains("unconnected_oks_ids\":[7,\"x\"]");
    }

    private VariantResult variant(String variantId, int rank) {
        return VariantResult.builder()
                .variantId(variantId)
                .segments(List.of())
                .chambers(List.of())
                .technicalNodes(List.of())
                .summary(summary(variantId, rank))
                .build();
    }

    private VariantSummary summary(String variantId, int rank) {
        return VariantSummary.builder()
                .variantId(variantId)
                .rank(rank)
                .constructionCost(10_000_000L)
                .chamberConstructionCost(3_000_000L)
                .existingChamberTieInCount(1)
                .existingChamberTieInCost(5_000_000L)
                .unconnectedPenalty(0L)
                .calculatedCost(10_000_000L)
                .newNetworkLengthM(100.0)
                .score(rank)
                .unconnectedOksIds(List.of())
                .numericOksIds(Set.of())
                .build();
    }
}
