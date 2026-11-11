package com.alxarafe.erbas.knowledge.pdf;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import com.alxarafe.erbas.knowledge.storage.application.*;
import com.alxarafe.erbas.knowledge.storage.domain.ContentDigest;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import static org.assertj.core.api.Assertions.*;

class PdfDocumentProcessorTests {
    private final PdfDocumentProcessor processor = new PdfDocumentProcessor();
    private final DocumentProcessingService service = new DocumentProcessingService(ProcessingLimits.DEFAULT, List.of(processor));

    @Test void extractsTextAndKeepsEveryOriginalPageIncludingEmptyPages() throws Exception {
        byte[] original = PdfFixtures.document("First café", "", "Third page");
        var content = service.process(new ByteArrayInputStream(original), PdfDocumentProcessor.PDF);
        assertThat(content.original()).isEqualTo(original);
        assertThat(content.sha256()).isEqualTo(ContentDigest.sha256(original));
        assertThat(content.text()).isEqualTo("First café\nThird page\n");
        assertThat(content.processing().pageEnds()).containsExactly("First café\n".length(), "First café\n".length(), content.text().length());
        assertThat(content.processing().processor()).isEqualTo(PdfDocumentProcessor.ID);
        assertThat(content.processing().version()).isEqualTo("3.0.8");
        assertThat(content.processing().options()).startsWith(PdfDocumentProcessor.OPTIONS)
                .contains(";java=" + Runtime.version());
        assertThat(service.process(new ByteArrayInputStream(original), PdfDocumentProcessor.PDF).text()).isEqualTo(content.text());
    }
    @Test void pdfTextUsesTheExistingUnicodeNormalization() throws Exception {
        byte[] original = PdfFixtures.documentWithActualText("Cafe\u0301");
        var content = service.process(new ByteArrayInputStream(original), PdfDocumentProcessor.PDF);
        assertThat(content.text()).isEqualTo("Café\n");
        assertThat(content.processing().pageEnds()).containsExactly(content.text().length());
        assertThat(content.original()).isEqualTo(original);
    }
    @Test void rejectsCorruptionEmptyTextAndEncryptionWithoutPartialResults() throws Exception {
        for (byte[] invalid : List.of(new byte[0], new byte[]{1, 2, 3},
                java.util.Arrays.copyOf(PdfFixtures.document("partial"), 50), PdfFixtures.brokenSecondPage()))
            failure(() -> service.process(new ByteArrayInputStream(invalid), PdfDocumentProcessor.PDF), ProcessingFailure.Kind.INVALID);
        failure(() -> service.process(new ByteArrayInputStream(PdfFixtures.document("", "")), PdfDocumentProcessor.PDF), ProcessingFailure.Kind.NO_TEXT);
        try (var document = Loader.loadPDF(PdfFixtures.document("secret")); var output = new java.io.ByteArrayOutputStream()) {
            var policy = new StandardProtectionPolicy("owner-fixture", "", new AccessPermission());
            document.protect(policy);
            document.save(output);
            failure(() -> service.process(new ByteArrayInputStream(output.toByteArray()), PdfDocumentProcessor.PDF), ProcessingFailure.Kind.INVALID);
        }
    }
    @Test void rejectsPageAndOutputLimitsEvenAfterAnEarlierPageWasExtracted() throws Exception {
        var defaults = ProcessingLimits.DEFAULT;
        byte[] original = PdfFixtures.document("First", "Long second page");
        var pages = new ProcessingLimits(defaults.originalBytes(), defaults.normalizedBytes(), 1, defaults.timeout());
        var output = new ProcessingLimits(defaults.originalBytes(), 8, defaults.pages(), defaults.timeout());
        for (var limits : List.of(pages, output)) {
            var limited = new DocumentProcessingService(limits, List.of(processor));
            failure(() -> limited.process(new ByteArrayInputStream(original), PdfDocumentProcessor.PDF), ProcessingFailure.Kind.LIMIT);
        }
    }
    @Test void deadlineIsCheckedDuringParserReads() throws Exception {
        var ticks = new AtomicLong();
        var limits = new ProcessingLimits(ProcessingLimits.DEFAULT.originalBytes(),
                ProcessingLimits.DEFAULT.normalizedBytes(), ProcessingLimits.DEFAULT.pages(), Duration.ofNanos(5));
        var budget = new ProcessingBudget(limits, ticks::getAndIncrement);
        byte[] original = PdfFixtures.document("deadline");
        failure(() -> processor.process(original, PdfDocumentProcessor.PDF, budget), ProcessingFailure.Kind.TIMEOUT);
    }
    private static void failure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ProcessingFailure.Kind kind) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ProcessingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(kind));
    }
}
