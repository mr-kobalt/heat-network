package ru.lct.heating.persistence;

import java.time.Instant;
import java.util.UUID;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "calculation_run")
@Getter
@Setter
public class CalculationRunEntity {

    @Id
    private UUID id;

    private UUID datasetId;

    private String status;

    private String algorithm;

    /** ADR-0036: запрошена поэтапная трассировка для визуализации. */
    private boolean trace;

    private Instant createdAt;

    private Instant startedAt;

    private Instant finishedAt;

    /** ADR-0057: текущий этап расчёта (машинный ключ) и прогресс 0…100. */
    private String stage;

    private Integer progress;

    @Column(columnDefinition = "text")
    private String error;

    @Column(columnDefinition = "text")
    private String summary;

    private String resultPath;
}
