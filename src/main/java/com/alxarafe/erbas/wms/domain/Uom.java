package com.alxarafe.erbas.wms.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/** Persisted catalogue value; normalization is independent of HTTP and storage. */
public record Uom(UUID id, String code, String description) {
    public static final int DESCRIPTION_MAXIMUM = 255;
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]{0,9}$");
    private static final UUID NIL_ID = new UUID(0, 0);

    public Uom {
        if (id == null || id.equals(NIL_ID) || code == null || !CODE_PATTERN.matcher(code).matches()
                || description == null) throw new IllegalArgumentException("Invalid unit of measure");
        int start = 0;
        int end = description.length();
        while (start < end && trimmable(description.charAt(start))) start++;
        while (end > start && trimmable(description.charAt(end - 1))) end--;
        description = description.substring(start, end);
        int length = 0;
        for (int index = 0; index < description.length(); index++, length++) {
            char character = description.charAt(index);
            if (character == '\0' || Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Invalid description scalar");
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= description.length() || !Character.isLowSurrogate(description.charAt(index + 1))) {
                    throw new IllegalArgumentException("Invalid description scalar");
                }
                index++;
            }
        }
        if (length == 0 || length > DESCRIPTION_MAXIMUM) {
            throw new IllegalArgumentException("Invalid description length");
        }
    }

    private static boolean trimmable(char character) {
        return character == ' ' || character == '\t' || character == '\r' || character == '\n';
    }
}
