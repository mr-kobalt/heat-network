package ru.lct.heating.geometry;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Правила по типам пространственных ограничений (ТП 5.1, протокол).
 */
@Data
@ConfigurationProperties(prefix = "heating.restrictions")
public class RestrictionRulesProperties {

    private RestrictionRule fallback = new RestrictionRule();
    private Map<String, RestrictionRule> rules = new LinkedHashMap<>();
}
