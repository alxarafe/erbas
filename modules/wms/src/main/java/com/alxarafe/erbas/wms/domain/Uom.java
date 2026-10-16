package com.alxarafe.erbas.wms.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/** Persisted catalogue value; normalization is independent of HTTP and storage. */
public record Uom(UUID id, String code, String description) {
    public static final int DESCRIPTION_MAXIMUM = CatalogueValues.TEXT_MAXIMUM;
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]{0,9}$");

    public Uom {
        CatalogueValues.requireIdentity(id);
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid unit of measure code");
        }
        description = CatalogueValues.normalizedText(description);
    }
}
