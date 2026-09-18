package ru.lct.heating.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalculationRunRepository extends JpaRepository<CalculationRunEntity, UUID> {

    List<CalculationRunEntity> findByDatasetIdOrderByCreatedAtDesc(UUID datasetId);
}
