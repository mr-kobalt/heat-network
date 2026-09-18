package ru.lct.heating.ingest;

import lombok.Builder;
import lombok.Value;
import ru.lct.heating.domain.NetworkDataset;

@Value
@Builder
public class IngestResult {
    NetworkDataset dataset;
    IngestDiagnostics diagnostics;
}
