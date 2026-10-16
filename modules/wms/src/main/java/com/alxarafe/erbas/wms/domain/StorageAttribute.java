package com.alxarafe.erbas.wms.domain;

import java.util.UUID;
import java.util.regex.Pattern;

public record StorageAttribute(UUID id, String code, String name) {
    public static final int CODE_MAXIMUM = 30;
    public static final int NAME_MAXIMUM = CatalogueValues.TEXT_MAXIMUM;
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0," + (CODE_MAXIMUM - 1) + "}$");

    public StorageAttribute {
        CatalogueValues.requireIdentity(id);
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid storage attribute code");
        }
        name = CatalogueValues.normalizedText(name);
    }
}
