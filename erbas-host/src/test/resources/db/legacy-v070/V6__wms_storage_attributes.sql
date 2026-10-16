-- WMS owns storage attributes. C collation gives exact uniqueness and ordinal ASCII ordering.
CREATE TABLE wms_storage_attributes (
    id UUID PRIMARY KEY,
    code TEXT COLLATE "C" NOT NULL UNIQUE,
    name TEXT NOT NULL
);
