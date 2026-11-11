package com.alxarafe.erbas.knowledge.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import com.alxarafe.erbas.knowledge.storage.application.*;
import com.alxarafe.erbas.knowledge.storage.domain.DocumentContent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DocumentProcessingTests {
    private static final ProcessingLimits LIMITS = new ProcessingLimits(32, 16, 2, Duration.ofSeconds(1));
    private final DocumentProcessor text = new TextDocumentProcessor();
    private final DocumentProcessingService service = new DocumentProcessingService(LIMITS, List.of(text));

    @Test void textAndMarkdownKeepTheirExistingNormalizationAndOriginal() {
        byte[] original = "\uFEFF# Cafe\u0301\r\n".getBytes(StandardCharsets.UTF_8);
        for (String type : text.contentTypes()) {
            var content = service.process(new ByteArrayInputStream(original), type);
            assertThat(content.original()).isEqualTo(original);
            assertThat(content.text()).isEqualTo("# Café\n");
            assertThat(content.processing().pageEnds()).isEmpty();
            assertThat(content.sha256()).isEqualTo(new DocumentContent(original, type).sha256());
        }
    }
    @Test void disabledAndAmbiguousProcessorsFailClosed() {
        var disabled = new DocumentProcessingService(LIMITS, List.of());
        failure(() -> disabled.process(InputStream.nullInputStream(), DocumentContent.TXT), ProcessingFailure.Kind.UNSUPPORTED);
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentProcessingService(LIMITS, List.of(text, text)));
    }
    @Test void inputAndUtf8OutputLimitsAreEnforcedAndStreamsBelongToCaller() {
        class Source extends ByteArrayInputStream {
            boolean closed;
            Source(byte[] data) { super(data); }
            @Override public void close() { closed = true; }
        }
        var input = new Source(new byte[LIMITS.originalBytes() + 10]);
        failure(() -> service.process(input, DocumentContent.TXT), ProcessingFailure.Kind.LIMIT);
        assertThat(input.closed).isFalse();
        assertThat(input.available()).isEqualTo(9);
        byte[] multibyte = "é".repeat(LIMITS.normalizedBytes()).getBytes(StandardCharsets.UTF_8);
        failure(() -> service.process(new ByteArrayInputStream(multibyte), DocumentContent.TXT), ProcessingFailure.Kind.LIMIT);
    }
    @Test void partialInputInvalidUtf8AndNulProduceNoResult() {
        var partial = new InputStream() {
            int reads;
            @Override public int read() throws IOException {
                if (reads++ == 0) return 'a';
                throw new IOException("fixture");
            }
        };
        failure(() -> service.process(partial, DocumentContent.TXT), ProcessingFailure.Kind.INVALID);
        for (byte[] bytes : List.of(new byte[]{(byte) 0xff}, new byte[]{0}))
            failure(() -> service.process(new ByteArrayInputStream(bytes), DocumentContent.TXT), ProcessingFailure.Kind.INVALID);
    }
    @Test void aProcessorCannotChangeTheOriginalOrReturnAfterItsDeadline() {
        DocumentProcessor broken = new DocumentProcessor() {
            public String id() { return "fixture"; }
            public Set<String> contentTypes() { return Set.of(DocumentContent.TXT); }
            public DocumentContent process(byte[] original, String type, ProcessingBudget budget) {
                original[0] = 'b';
                return new DocumentContent(original, type);
            }
        };
        failure(() -> new DocumentProcessingService(LIMITS, List.of(broken)).process(
                new ByteArrayInputStream(new byte[]{'a'}), DocumentContent.TXT), ProcessingFailure.Kind.INVALID);
        var clock = new AtomicLong();
        var budget = new ProcessingBudget(LIMITS, clock::get);
        clock.set(LIMITS.timeout().toNanos());
        failure(budget::check, ProcessingFailure.Kind.TIMEOUT);
    }
    private static void failure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ProcessingFailure.Kind kind) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ProcessingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(kind));
    }
}
