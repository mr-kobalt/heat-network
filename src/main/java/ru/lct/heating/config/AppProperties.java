package ru.lct.heating.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "heating.app")
public class AppProperties {

    private String storageRoot = "./data";
    private int workerThreads = 1;
    private int defaultDiameterMm = 400;
    private double bendSurcharge = 1.5;
    private int maxRunHistory = 50;
    private List<String> allowedOrigins = new ArrayList<>(
            List.of("http://localhost:5173", "http://localhost:8081"));
}
