-- WMS owns this catalogue. C collation gives ordinal ASCII code ordering.
CREATE TABLE wms_storage_attributes (
    id UUID PRIMARY KEY,
    code TEXT COLLATE "C" NOT NULL UNIQUE,
    name TEXT NOT NULL
);
