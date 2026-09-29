package ru.lct.heating.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * ADR-0075: контракт OpenAPI/Swagger. Полный Spring-контекст с БД
 * (Testcontainers PostGIS; при недоступности Docker — внешняя БД).
 *
 * <p>Запуск: {@code mvn test -Dtest=OpenApiDocsSlowTest
 * -Dsurefire.excludedGroups= -Dgroups=slow}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("slow")
class OpenApiDocsSlowTest {

    private static final PostgreSQLContainer<?> POSTGIS = startPostgis();

    private static PostgreSQLContainer<?> startPostgis() {
        try {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgis/postgis:16-3.4")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("heating")
                    .withUsername("heating")
                    .withPassword("heating");
            container.start();
            return container;
        } catch (Throwable unavailable) {
            System.out.println("PostGIS Testcontainer недоступен, используется внешняя БД: "
                    + unavailable.getMessage());
            return null;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (POSTGIS != null) {
            registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGIS::getUsername);
            registry.add("spring.datasource.password", POSTGIS::getPassword);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void exposesDocumentedOpenApiAndSwaggerUi() throws Exception {
        ResponseEntity<String> docsResponse = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(docsResponse.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode root = new ObjectMapper().readTree(docsResponse.getBody());

        // Группы операций (теги).
        Set<String> tags = new HashSet<>();
        root.path("tags").forEach(tag -> tags.add(tag.path("name").asText()));
        assertThat(tags).contains("Dataset", "Runs", "Algorithms", "Service");

        // Ключевые пути.
        JsonNode paths = root.path("paths");
        assertThat(paths.has("/api/v1/datasets")).isTrue();
        assertThat(paths.has("/api/v1/datasets/{datasetId}")).isTrue();
        assertThat(paths.has("/api/v1/datasets/{datasetId}/runs")).isTrue();
        assertThat(paths.has("/api/v1/runs/{runId}")).isTrue();
        assertThat(paths.has("/api/v1/runs/{runId}/result")).isTrue();
        assertThat(paths.has("/api/v1/runs/{runId}/stages")).isTrue();
        assertThat(paths.has("/api/v1/runs/{runId}/stages/{stageId}")).isTrue();
        assertThat(paths.has("/api/v1/algorithms")).isTrue();
        assertThat(paths.has("/api/v1/info")).isTrue();

        // Описание и схемы.
        assertThat(root.path("info").path("title").asText()).contains("Heating Routing Service");
        assertThat(paths.path("/api/v1/datasets/{datasetId}/runs").path("post")
                .path("summary").asText()).isNotBlank();
        JsonNode schemas = root.path("components").path("schemas");
        assertThat(schemas.has("DatasetResponse")).isTrue();
        assertThat(schemas.has("RunResponse")).isTrue();
        assertThat(schemas.has("AlgorithmInfo")).isTrue();
        assertThat(schemas.has("ErrorResponse")).isTrue();
        assertThat(schemas.path("RunResponse").path("properties").has("mode")).isTrue();

        // Служебная информация: версия из build-info, статус готовности.
        ResponseEntity<String> info = restTemplate.getForEntity("/api/v1/info", String.class);
        assertThat(info.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode infoNode = new ObjectMapper().readTree(info.getBody());
        assertThat(infoNode.path("status").asText()).isEqualTo("ready");
        assertThat(infoNode.path("version").asText()).isNotBlank();

        // Swagger UI доступен.
        ResponseEntity<String> ui = restTemplate.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(ui.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
