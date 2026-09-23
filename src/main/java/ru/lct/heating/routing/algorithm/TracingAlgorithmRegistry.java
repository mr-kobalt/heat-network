package ru.lct.heating.routing.algorithm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.lct.heating.config.AppProperties;

/**
 * Реестр подключаемых алгоритмов трассировки (ADR-0027): все бины
 * {@link TracingAlgorithm} собираются Spring, дубликаты id запрещены.
 */
@Component
public class TracingAlgorithmRegistry {

    private final Map<String, TracingAlgorithm> algorithms = new LinkedHashMap<>();
    private final String defaultId;

    public TracingAlgorithmRegistry(List<TracingAlgorithm> algorithms,
                                    AppProperties appProperties) {
        for (TracingAlgorithm algorithm : algorithms) {
            if (this.algorithms.putIfAbsent(algorithm.id(), algorithm) != null) {
                throw new IllegalStateException(
                        "Дублирующийся id алгоритма трассировки: " + algorithm.id());
            }
        }
        this.defaultId = appProperties.getRoutingAlgorithm();
        if (defaultId == null || !this.algorithms.containsKey(defaultId)) {
            throw new IllegalStateException("Алгоритм по умолчанию не найден: " + defaultId
                    + "; доступные: " + this.algorithms.keySet());
        }
    }

    /**
     * Список алгоритмов с признаком алгоритма по умолчанию, отсортирован по id.
     * Устаревшие (E25-09) не показываются.
     */
    public List<AlgorithmInfo> available() {
        List<AlgorithmInfo> result = new ArrayList<>(algorithms.size());
        for (TracingAlgorithm algorithm : algorithms.values()) {
            if (algorithm.deprecated()) {
                continue;
            }
            result.add(AlgorithmInfo.builder()
                    .id(algorithm.id())
                    .description(algorithm.description())
                    .defaultAlgorithm(algorithm.id().equals(defaultId))
                    .build());
        }
        result.sort(Comparator.comparing(AlgorithmInfo::getId));
        return result;
    }

    /**
     * Разрешение алгоритма по id; пустой id — алгоритм по умолчанию. Неизвестный
     * id → 400 с перечнем доступных.
     */
    public TracingAlgorithm require(String id) {
        String resolved = (id == null || id.isBlank()) ? defaultId : id;
        TracingAlgorithm algorithm = algorithms.get(resolved);
        if (algorithm == null || algorithm.deprecated()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Неизвестный алгоритм трассировки: " + id
                            + "; доступные: " + available().stream()
                                    .map(AlgorithmInfo::getId)
                                    .collect(java.util.stream.Collectors.toList()));
        }
        return algorithm;
    }

    public TracingAlgorithm defaultAlgorithm() {
        return algorithms.get(defaultId);
    }

    public String defaultId() {
        return defaultId;
    }
}
