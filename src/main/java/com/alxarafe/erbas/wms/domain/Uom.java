package com.alxarafe.erbas.wms.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/** Persisted catalogue value; normalization is independent of HTTP and storage. */
public record Uom(UUID id, String code, String description) {
    public static final int DESCRIPTION_MAXIMUM = CatalogueLabel.MAXIMUM;
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]{0,9}$");
    private static final UUID NIL_ID = new UUID(0, 0);

    public Uom {
        if (id == null || id.equals(NIL_ID) || code == null || !CODE_PATTERN.matcher(code).matches()
                || description == null) throw new IllegalArgumentException("Invalid unit of measure");
        description = CatalogueLabel.normalize(description, DESCRIPTION_MAXIMUM);
    }
}
