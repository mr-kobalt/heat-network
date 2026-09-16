package ru.lct.heating.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI heatingRoutingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Heating Routing Service API")
                        .description("Сервис моделирования трасс подключения перспективных ОКС "
                                + "к существующей тепловой сети (ЛЦТ 2026)")
                        .version("0.1.0")
                        .license(new License().name("Proprietary")));
    }
}
