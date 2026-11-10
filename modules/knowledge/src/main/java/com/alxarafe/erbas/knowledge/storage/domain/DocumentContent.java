package com.alxarafe.erbas.knowledge.storage.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Objects;
import java.util.Set;

/** Original UTF-8 bytes and deterministic text; Markdown syntax is preserved. */
public final class DocumentContent {
    public static final String TXT = "text/plain";
    public static final String MARKDOWN = "text/markdown";
    private static final Set<String> SUPPORTED_TYPES = Set.of(TXT, MARKDOWN);
    private final byte[] original;
    private final String contentType;
    private final String text;
    private final String sha256;

    public DocumentContent(byte[] original, String contentType) {
        if (!SUPPORTED_TYPES.contains(Objects.requireNonNull(contentType)))
            throw new IllegalArgumentException("Unsupported document content type");
        this.original = Objects.requireNonNull(original).clone();
        this.contentType = contentType;
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(this.original)).toString();
            if (decoded.indexOf('\0') >= 0) throw new IllegalArgumentException("Document text contains NUL");
            if (decoded.startsWith("\uFEFF")) decoded = decoded.substring(1);
            text = Normalizer.normalize(decoded.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC);
            sha256 = ContentDigest.sha256(this.original);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Document must be UTF-8", exception);
        }
    }
    public byte[] original() { return original.clone(); }
    public String contentType() { return contentType; }
    public String text() { return text; }
    public String sha256() { return sha256; }
}
