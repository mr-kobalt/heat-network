package ru.lct.heating.routing;

import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.lct.heating.config.AppProperties;

/**
 * Выбор хранилища состояния сетки (ADR-0033, фаза B): память или PostGIS TEMP
 * при превышении бюджета клеток.
 */
@Component
public class CellStoreFactory {

    private final AppProperties properties;
    private final DataSource dataSource;

    public CellStoreFactory(AppProperties properties,
                            @Autowired(required = false) DataSource dataSource) {
        this.properties = properties;
        this.dataSource = dataSource;
    }

    public boolean spillEnabled(long cells) {
        String mode = properties.getForestGridStorage();
        if ("memory".equalsIgnoreCase(mode)) {
            return false;
        }
        if ("postgis".equalsIgnoreCase(mode)) {
            return dataSource != null;
        }
        return dataSource != null && cells > properties.getForestGridMaxCells();
    }

    public CellStore create(int cells, List<String> warnings) {
        if (spillEnabled(cells)) {
            try {
                return new PostgisCellStore(dataSource.getConnection(),
                        properties.getForestGridPageCells(),
                        properties.getForestGridCacheMb(), cells);
            } catch (SQLException exception) {
                warnings.add("GRID_SPILL_FALLBACK: " + exception.getMessage());
            }
        }
        return new InMemoryCellStore(cells);
    }
}
