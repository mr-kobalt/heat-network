package ru.lct.heating.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DatasetRepository extends JpaRepository<DatasetEntity, UUID> {
}
