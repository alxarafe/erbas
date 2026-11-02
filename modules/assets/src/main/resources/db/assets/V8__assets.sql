CREATE TABLE assets_assets (
    id UUID PRIMARY KEY,
    code VARCHAR(64) COLLATE "C" NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    serial_number VARCHAR(255),
    lifecycle VARCHAR(7) NOT NULL CHECK (lifecycle IN ('DRAFT', 'READY', 'RETIRED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
