CREATE TABLE IF NOT EXISTS dataset (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    original_filename VARCHAR(512),
    object_counts TEXT,
    bbox VARCHAR(256),
    status VARCHAR(32) NOT NULL,
    diagnostics TEXT
);

CREATE TABLE IF NOT EXISTS calculation_run (
    id UUID PRIMARY KEY,
    dataset_id UUID NOT NULL REFERENCES dataset (id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    error TEXT,
    summary TEXT,
    result_path VARCHAR(1024)
);

CREATE INDEX IF NOT EXISTS idx_calculation_run_dataset ON calculation_run (dataset_id);
