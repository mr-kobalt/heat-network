package ru.lct.heating.routing.algorithm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.routing.ConnectionExit;

class TracingAlgorithmRegistryTest {

    @Test
    void available_marksDefault() {
        AppProperties properties = new AppProperties();
        properties.setRoutingAlgorithm("a");
        TracingAlgorithmRegistry registry = new TracingAlgorithmRegistry(
                List.of(fake("b"), fake("a")), properties);

        List<AlgorithmInfo> available = registry.available();

        assertThat(available).extracting(AlgorithmInfo::getId).containsExactly("a", "b");
        assertThat(available.get(0).isDefaultAlgorithm()).isTrue();
        assertThat(registry.require(null).id()).isEqualTo("a");
        assertThat(registry.require(" ").id()).isEqualTo("a");
    }

    @Test
    void require_unknown_returnsBadRequest() {
        AppProperties properties = new AppProperties();
        properties.setRoutingAlgorithm("a");
        TracingAlgorithmRegistry registry = new TracingAlgorithmRegistry(
                List.of(fake("a")), properties);

        assertThatThrownBy(() -> registry.require("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void duplicateId_failsFast() {
        AppProperties properties = new AppProperties();
        properties.setRoutingAlgorithm("a");
        assertThatThrownBy(() -> new TracingAlgorithmRegistry(
                List.of(fake("a"), fake("a")), properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownDefault_failsFast() {
        AppProperties properties = new AppProperties();
        properties.setRoutingAlgorithm("missing");
        assertThatThrownBy(() -> new TracingAlgorithmRegistry(List.of(fake("a")), properties))
                .isInstanceOf(IllegalStateException.class);
    }

    private TracingAlgorithm fake(String id) {
        return new TracingAlgorithm() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String description() {
                return "fake " + id;
            }

            @Override
            public List<ru.lct.heating.routing.ForestPlanningResult> plan(
                    ru.lct.heating.domain.NetworkDataset dataset,
                    ru.lct.heating.graph.ExistingNetworkGraph graph,
                    ru.lct.heating.geometry.ObstacleIndex obstacleIndex,
                    List<String> warnings,
                    Map<String, ConnectionExit> exits) {
                return List.of();
            }
        };
    }
}
