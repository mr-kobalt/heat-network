package ru.lct.heating.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Регресс на привязку настроек (ADR-0040/0041): каждое конфигурируемое поле
 * {@link AppProperties} должно иметь ключ в {@code application.yml}, а ключи с
 * env-переопределением — использовать имя вида {@code HEATING_<ПОЛЕ>}.
 * Ловит класс ошибки «добавили поле, но забыли env/yml» (как с
 * {@code forest-relink-exit-candidates}).
 */
class AppPropertiesEnvBindingTest {

    /** Поля, которые намеренно не задаются извне (значения зафиксированы в коде). */
    private static final Set<String> NOT_CONFIGURABLE = Set.of(
            "defaultDiameterMm", "roundingToleranceM", "chamberTieInRadiusM");

    @Test
    void everyConfigurableFieldHasYamlKey() throws Exception {
        Map<String, Object> app = appSection();
        for (Field field : AppProperties.class.getDeclaredFields()) {
            if (NOT_CONFIGURABLE.contains(field.getName())) {
                continue;
            }
            String key = kebab(field.getName());
            assertThat(app)
                    .as("ключ %s в application.yml для поля %s", key, field.getName())
                    .containsKey(key);
        }
    }

    @Test
    void envBackedKeysFollowHeatingNamingConvention() throws Exception {
        Map<String, Object> app = appSection();
        for (Field field : AppProperties.class.getDeclaredFields()) {
            if (NOT_CONFIGURABLE.contains(field.getName())) {
                continue;
            }
            Object value = app.get(kebab(field.getName()));
            if (!(value instanceof String) || !((String) value).startsWith("${")) {
                continue;
            }
            String expected = "${HEATING_" + field.getName()
                    .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                    .toUpperCase() + ":";
            assertThat((String) value)
                    .as("env-имя для поля %s", field.getName())
                    .startsWith(expected);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> appSection() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(stream).as("application.yml на classpath").isNotNull();
            Map<String, Object> root = new Yaml().load(stream);
            Map<String, Object> heating = (Map<String, Object>) root.get("heating");
            return (Map<String, Object>) heating.get("app");
        }
    }

    private String kebab(String camel) {
        StringBuilder result = new StringBuilder();
        for (char ch : camel.toCharArray()) {
            if (Character.isUpperCase(ch)) {
                result.append('-').append(Character.toLowerCase(ch));
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }
}
