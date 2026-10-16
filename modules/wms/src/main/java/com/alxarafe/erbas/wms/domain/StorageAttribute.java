package com.alxarafe.erbas.wms.domain;

import java.util.UUID;

public record StorageAttribute(UUID id, String code, String name) {
    public static final int CODE_MAXIMUM = CatalogueValues.CODE_MAXIMUM;
    public static final int NAME_MAXIMUM = CatalogueValues.TEXT_MAXIMUM;

    public StorageAttribute {
        CatalogueValues.requireIdentity(id);
        CatalogueValues.requireCatalogueCode(code);
        name = CatalogueValues.normalizedText(name);
    }
}
