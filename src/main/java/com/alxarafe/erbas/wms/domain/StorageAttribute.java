package com.alxarafe.erbas.wms.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/** Persisted Storage Attribute; no family associations or storage rules. */
public record StorageAttribute(UUID id, String code, String name) {
    public static final int NAME_MAXIMUM = CatalogueLabel.MAXIMUM;
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,29}$");
    private static final UUID NIL_ID = new UUID(0, 0);

    public StorageAttribute {
        if (id == null || id.equals(NIL_ID) || code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid storage attribute");
        }
        name = CatalogueLabel.normalize(name, NAME_MAXIMUM);
    }
}
