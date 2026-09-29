package ru.lct.heating.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Служебная информация о сервисе (доступность, версия сборки).
 */
@Tag(name = "Service", description = "Служебные эндпоинты (информация о сервисе).")
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class InfoController {

    private final ObjectProvider<BuildProperties> buildProperties;

    public InfoController(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @Operation(summary = "Информация о сервисе",
            description = "Возвращает имя сервиса, версию сборки и статус готовности. "
                    + "Не требует БД; удобно для проверки доступности.")
    @ApiResponse(responseCode = "200", description = "Информация о сервисе",
            content = @Content(schema = @Schema(example = "{\"service\":\"heating-routing-service\","
                    + "\"version\":\"0.1.0\",\"status\":\"ready\"}")))
    @GetMapping("/info")
    public Map<String, String> info() {
        BuildProperties build = buildProperties.getIfAvailable();
        Map<String, String> info = new LinkedHashMap<>();
        info.put("service", "heating-routing-service");
        info.put("version", build != null ? build.getVersion() : "dev");
        if (build != null && build.getTime() != null) {
            info.put("buildTime", build.getTime().toString());
        }
        info.put("status", "ready");
        return info;
    }
}
