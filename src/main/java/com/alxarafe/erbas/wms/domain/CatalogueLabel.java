package com.alxarafe.erbas.wms.domain;

/** Exact WMS catalogue boundary normalization and Unicode scalar validation. */
final class CatalogueLabel {
    static final int MAXIMUM = 255;

    private CatalogueLabel() { }

    static String normalize(String value, int maximum) {
        if (value == null) throw new IllegalArgumentException("Missing catalogue label");
        int start = 0;
        int end = value.length();
        while (start < end && trimmable(value.charAt(start))) start++;
        while (end > start && trimmable(value.charAt(end - 1))) end--;
        value = value.substring(start, end);
        int length = 0;
        for (int index = 0; index < value.length(); index++, length++) {
            char character = value.charAt(index);
            if (character == '\0' || Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Invalid catalogue label scalar");
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("Invalid catalogue label scalar");
                }
                index++;
            }
        }
        if (length == 0 || length > maximum) {
            throw new IllegalArgumentException("Invalid catalogue label length");
        }
        return value;
    }

    private static boolean trimmable(char character) {
        return character == ' ' || character == '\t' || character == '\r' || character == '\n';
    }
}
