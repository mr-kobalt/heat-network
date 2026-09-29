package ru.lct.heating.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Описание OpenAPI/Swagger сервиса (ADR-0075): общая информация, серверы и
 * группы операций (теги). Детальные описания операций — аннотации в контроллерах,
 * модели — {@code @Schema} на DTO.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI heatingRoutingOpenApi() {
        String description = "Сервис автоматического построения трасс подключения "
                + "перспективных ОКС к существующей тепловой сети (кейс ЛЦТ 2026).\n\n"
                + "**Сценарий работы**\n\n"
                + "1. `POST /api/v1/datasets` — загрузить совмещённый GeoJSON (до 3 ГБ, потоково).\n"
                + "2. `POST /api/v1/datasets/{id}/runs` — запустить расчёт "
                + "(необязательно `algorithm`, `mode`, `trace`).\n"
                + "3. `GET /api/v1/runs/{id}` — опрашивать статус и прогресс до "
                + "`DONE`/`PARTIAL`/`FAILED`.\n"
                + "4. `GET /api/v1/runs/{id}/result` — скачать результат (GeoJSON).\n"
                + "5. `GET /api/v1/runs/{id}/stages[/{stageId}]` — промежуточные этапы "
                + "(при `trace=true`).\n\n"
                + "Расчёт выполняется офлайн; вход и выход GeoJSON — формат из "
                + "`docs/02-domain/data-model.md`, правила — ТП v2.";
        return new OpenAPI()
                .info(new Info()
                        .title("Heating Routing Service API")
                        .description(description)
                        .version("0.1.0")
                        .license(new License().name("Proprietary"))
                        .contact(new Contact().name("Команда ЛЦТ 2026")))
                .servers(List.of(
                        new Server().url("http://localhost:8080")
                                .description("Локальный запуск"),
                        new Server().url("/")
                                .description("Стек с визуализатором (относительный)")))
                .tags(List.of(
                        tag("Service", "Служебные эндпоинты (информация о сервисе)."),
                        tag("Dataset", "Загрузка и чтение датасетов (входной GeoJSON)."),
                        tag("Runs", "Запуск расчёта, статус, результат и этапы."),
                        tag("Algorithms", "Доступные алгоритмы трассировки (ADR-0027).")));
    }

    private Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }
}
