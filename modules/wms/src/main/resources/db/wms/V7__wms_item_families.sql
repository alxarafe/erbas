-- Functional WMS ownership; ordinal catalogue order and exact pair identity.
CREATE TABLE wms_item_families (
    id UUID PRIMARY KEY,
    code TEXT COLLATE "C" NOT NULL UNIQUE,
    name TEXT NOT NULL
);

CREATE TABLE wms_item_family_storage_attributes (
    item_family_id UUID NOT NULL REFERENCES wms_item_families(id),
    storage_attribute_id UUID NOT NULL REFERENCES wms_storage_attributes(id),
    PRIMARY KEY (item_family_id, storage_attribute_id)
);
CREATE INDEX wms_item_family_storage_attributes_attribute_idx
    ON wms_item_family_storage_attributes (storage_attribute_id);
