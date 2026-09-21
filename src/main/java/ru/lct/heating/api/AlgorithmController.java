package ru.lct.heating.api;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.lct.heating.routing.algorithm.AlgorithmInfo;
import ru.lct.heating.routing.algorithm.TracingAlgorithmRegistry;

/**
 * Доступные алгоритмы трассировки (ADR-0027).
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class AlgorithmController {

    private final TracingAlgorithmRegistry registry;

    public AlgorithmController(TracingAlgorithmRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/algorithms")
    public List<AlgorithmInfo> list() {
        return registry.available();
    }
}
