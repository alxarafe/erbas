-- WMS owns this catalogue. C collation gives ordinal ASCII code ordering.
CREATE TABLE wms_uoms (
    id UUID PRIMARY KEY,
    code TEXT COLLATE "C" NOT NULL UNIQUE,
    description TEXT NOT NULL
);
