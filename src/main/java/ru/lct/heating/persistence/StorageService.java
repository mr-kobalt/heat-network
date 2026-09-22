package ru.lct.heating.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.lct.heating.config.AppProperties;

/**
 * Раскладка файлов рабочего каталога: датасеты и результаты расчётов.
 */
@Component
public class StorageService {

    private final Path root;

    public StorageService(AppProperties properties) {
        this.root = Paths.get(properties.getStorageRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root.resolve("datasets"));
            Files.createDirectories(root.resolve("runs"));
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось создать рабочий каталог: " + root, exception);
        }
    }

    public Path root() {
        return root;
    }

    public Path datasetFile(UUID datasetId) {
        return directory(root.resolve("datasets"), datasetId).resolve("input.geojson");
    }

    public Path datasetDiagnosticsFile(UUID datasetId) {
        return directory(root.resolve("datasets"), datasetId).resolve("diagnostics.json");
    }

    public Path runResultFile(UUID runId) {
        return directory(root.resolve("runs"), runId).resolve("result.geojson");
    }

    public Path runSummaryFile(UUID runId) {
        return directory(root.resolve("runs"), runId).resolve("summary.json");
    }

    public Path runWarningsFile(UUID runId) {
        return directory(root.resolve("runs"), runId).resolve("warnings.json");
    }

    /** Каталог промежуточных этапов расчёта (ADR-0036); создаётся писателем. */
    public Path runStageDir(UUID runId) {
        return directory(root.resolve("runs"), runId).resolve("stages");
    }

    private Path directory(Path parent, UUID id) {
        Path directory = parent.resolve(id.toString());
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось создать каталог: " + directory, exception);
        }
        return directory;
    }
}
