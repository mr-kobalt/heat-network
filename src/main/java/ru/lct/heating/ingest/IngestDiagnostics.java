package ru.lct.heating.ingest;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * Результат разбора и валидации входного файла.
 */
@Value
@Builder
public class IngestDiagnostics {
    long totalFeatures;
    Map<String, Integer> countsByType;
    double[] bbox;
    List<IngestWarning> warnings;
    List<String> errors;

    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }
}
