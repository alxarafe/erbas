package com.alxarafe.erbas.ai;

import java.util.regex.Pattern;

/** JSON Schema/ECMAScript whitespace and Unicode scalar semantics, not UTF-16 length. */
public final class AiText {
    public static final int MESSAGE_MAXIMUM_SCALARS = 4000;
    private static final Pattern NONBLANK = Pattern.compile(
            "[^\\s\\x{00a0}\\x{1680}\\x{2000}-\\x{200a}\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}\\x{feff}]");
    private AiText() { }
    public static boolean validMessage(String value) {
        return validAnswer(value) && value.codePointCount(0, value.length()) <= MESSAGE_MAXIMUM_SCALARS;
    }
    public static boolean validAnswer(String value) {
        if (value == null || !NONBLANK.matcher(value).find()) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\0' || Character.isLowSurrogate(character)) return false;
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) return false;
                index++;
            }
        }
        return true;
    }
}
