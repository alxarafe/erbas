package com.alxarafe.erbas.wms.domain;

import java.util.UUID;

/** Shared persisted identity and user-text semantics of the two WMS catalogues. */
public final class CatalogueValues {
    public static final int TEXT_MAXIMUM = 255;
    private static final UUID NIL_ID = new UUID(0, 0);

    private CatalogueValues() { }

    public static void requireIdentity(UUID id) {
        if (id == null || id.equals(NIL_ID)) throw new IllegalArgumentException("Invalid WMS identity");
    }

    public static String normalizedText(String value) {
        if (value == null) throw new IllegalArgumentException("Missing catalogue text");
        int start = 0;
        int end = value.length();
        while (start < end && trimmable(value.charAt(start))) start++;
        while (end > start && trimmable(value.charAt(end - 1))) end--;
        value = value.substring(start, end);
        int length = 0;
        for (int index = 0; index < value.length(); index++, length++) {
            char character = value.charAt(index);
            if (character == '\0' || Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Invalid catalogue text scalar");
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("Invalid catalogue text scalar");
                }
                index++;
            }
        }
        if (length == 0 || length > TEXT_MAXIMUM) {
            throw new IllegalArgumentException("Invalid catalogue text length");
        }
        return value;
    }

    private static boolean trimmable(char character) {
        return character == ' ' || character == '\t' || character == '\r' || character == '\n';
    }
}
