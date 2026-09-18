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
@Table(name = "dataset")
@Getter
@Setter
public class DatasetEntity {

    @Id
    private UUID id;

    private Instant createdAt;

    private String originalFilename;

    @Column(columnDefinition = "text")
    private String objectCounts;

    private String bbox;

    private String status;

    @Column(columnDefinition = "text")
    private String diagnostics;
}
