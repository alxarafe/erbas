package com.alxarafe.erbas.knowledge.storage.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ContentDigest {
    private static final String ALGORITHM = "SHA-256";
    private ContentDigest() { }
    public static MessageDigest newDigest() {
        try { return MessageDigest.getInstance(ALGORITHM); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String hex(byte[] digest) { return HexFormat.of().formatHex(digest); }
    public static String sha256(byte[] bytes) { return hex(newDigest().digest(bytes)); }
}
