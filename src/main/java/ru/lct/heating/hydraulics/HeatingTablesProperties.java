package ru.lct.heating.hydraulics;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Условные диаметры, пропускная способность, предельная длина и стоимость
 * (ТП 4.1). Единый источник значений — конфигурация.
 */
@Data
@ConfigurationProperties(prefix = "heating")
public class HeatingTablesProperties {

    private List<DiameterRow> diameters = new ArrayList<>();
}
