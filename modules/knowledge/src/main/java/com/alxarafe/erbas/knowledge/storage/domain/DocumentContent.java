package com.alxarafe.erbas.knowledge.storage.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Objects;
import java.util.Set;

/** Original bytes and normalized text; Markdown syntax is preserved. */
public final class DocumentContent {
    public static final String TXT = "text/plain";
    public static final String MARKDOWN = "text/markdown";
    public static final String LINE_SEPARATOR = "\n";
    private static final Set<String> SUPPORTED_TYPES = Set.of(TXT, MARKDOWN);
    private final byte[] original;
    private final String contentType;
    private final String text;
    private final String sha256;
    private final ProcessingDetails processing;

    public DocumentContent(byte[] original, String contentType) {
        if (!SUPPORTED_TYPES.contains(Objects.requireNonNull(contentType)))
            throw new IllegalArgumentException("Unsupported document content type");
        this.original = Objects.requireNonNull(original).clone();
        this.contentType = contentType;
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(this.original)).toString();
            text = normalize(decoded);
            sha256 = ContentDigest.sha256(this.original);
            processing = ProcessingDetails.TEXT;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Document must be UTF-8", exception);
        }
    }
    private DocumentContent(byte[] original, String contentType, String text, ProcessingDetails processing) {
        this.original = Objects.requireNonNull(original).clone();
        this.contentType = Objects.requireNonNull(contentType);
        if (contentType.isBlank() || !normalize(text).equals(text))
            throw new IllegalArgumentException("Content must be normalized");
        this.text = text;
        this.processing = Objects.requireNonNull(processing);
        if (!processing.pageEnds().isEmpty() && processing.pageEnds().getLast() != text.length())
            throw new IllegalArgumentException("Page provenance must cover the complete text");
        this.sha256 = ContentDigest.sha256(this.original);
    }

    /** Trusted internal processing/persistence boundary, never an authorization grant. */
    public static DocumentContent processed(byte[] original, String contentType, String text, ProcessingDetails processing) {
        return new DocumentContent(original, contentType, text, processing);
    }

    public static String normalize(String decoded) {
        if (decoded.indexOf('\0') >= 0) throw new IllegalArgumentException("Document text contains NUL");
        if (decoded.startsWith("\uFEFF")) decoded = decoded.substring(1);
        return Normalizer.normalize(decoded.replace("\r\n", LINE_SEPARATOR).replace("\r", LINE_SEPARATOR), Normalizer.Form.NFC);
    }
    public byte[] original() { return original.clone(); }
    public String contentType() { return contentType; }
    public String text() { return text; }
    public String sha256() { return sha256; }
    public ProcessingDetails processing() { return processing; }
}
