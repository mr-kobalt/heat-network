package ru.lct.heating.routing;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Builder;
import lombok.Value;

/**
 * Дерево подключения одного кластера ОКС к сети (ADR-0019).
 */
@Value
@Builder
public class ForestTree {
    String tieInNodeId;
    Map<String, ForestNode> nodes;
    List<ForestEdge> edges;

    public Optional<ForestNode> node(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public ForestNode requireNode(String id) {
        ForestNode node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("Неизвестный узел дерева: " + id);
        }
        return node;
    }
}
