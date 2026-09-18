package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Разрешение правила по {@code restriction_type} с учётом алиасов
 * и запасного правила для неизвестных типов (ADR-0009).
 */
@Component
public class RestrictionRuleResolver {

    private final RestrictionRulesProperties properties;

    public RestrictionRuleResolver(RestrictionRulesProperties properties) {
        this.properties = properties;
    }

    public RestrictionRule resolve(String restrictionType) {
        if (restrictionType == null) {
            return properties.getFallback();
        }
        RestrictionRule rule = properties.getRules().get(restrictionType);
        return rule != null ? rule : properties.getFallback();
    }

    public boolean isKnownType(String restrictionType) {
        return restrictionType != null && properties.getRules().containsKey(restrictionType);
    }

    public List<String> knownTypes() {
        return new ArrayList<>(properties.getRules().keySet());
    }
}
