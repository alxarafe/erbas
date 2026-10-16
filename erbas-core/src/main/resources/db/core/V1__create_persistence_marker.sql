CREATE TABLE erbas_persistence_marker (
    marker_id INTEGER PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO erbas_persistence_marker (marker_id)
VALUES (1);
