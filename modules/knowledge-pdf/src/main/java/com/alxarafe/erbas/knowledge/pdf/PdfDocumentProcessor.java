package com.alxarafe.erbas.knowledge.pdf;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Set;
import com.alxarafe.erbas.knowledge.storage.application.*;
import com.alxarafe.erbas.knowledge.storage.domain.DocumentContent;
import com.alxarafe.erbas.knowledge.storage.domain.ProcessingDetails;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Version;

/** Text layer only. No rendering, OCR, embedded-file extraction, external resources or password support. */
public final class PdfDocumentProcessor implements DocumentProcessor {
    public static final String ID = "knowledge-pdfbox";
    public static final String PDF = "application/pdf";
    public static final String OPTIONS = "algorithm=1;parser=strict;encrypted=reject;sort=content;page-end=lf;line=lf;"
            + ProcessingDetails.TEXT_OPTIONS + ";page-offsets=utf16";
    public String id() { return ID; }
    public Set<String> contentTypes() { return Set.of(PDF); }

    public DocumentContent process(byte[] original, String contentType, ProcessingBudget budget) {
        if (!PDF.equals(contentType)) throw new ProcessingFailure(ProcessingFailure.Kind.UNSUPPORTED);
        if (original.length > budget.limits().originalBytes()) throw new ProcessingFailure(ProcessingFailure.Kind.LIMIT);
        budget.check();
        // Bounded bytes use PDFBox random access without physical paths or temporary files.
        try (var source = new RandomAccessReadBuffer(original) {
            @Override public int read() throws IOException { budget.check(); return super.read(); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                budget.check(); return super.read(bytes, offset, length);
            }
        }) {
            var parser = new PDFParser(source);
            try (var document = parser.parse(false)) {
                budget.check();
                if (document.isEncrypted()) throw new ProcessingFailure(ProcessingFailure.Kind.INVALID);
                int count = document.getNumberOfPages();
                if (count > budget.limits().pages()) throw new ProcessingFailure(ProcessingFailure.Kind.LIMIT);
                var ends = new ArrayList<Integer>();
                var text = new StringBuilder();
                var staged = new StringBuilder();
                var stripper = new PDFTextStripper() {
                    private int glyphs;
                    @Override protected void processTextPosition(TextPosition position) {
                        budget.check();
                        if (++glyphs > budget.limits().normalizedBytes()) throw new ProcessingFailure(ProcessingFailure.Kind.LIMIT);
                        super.processTextPosition(position);
                    }
                    @Override protected void startPage(PDPage page) throws IOException {
                        budget.check(); super.startPage(page);
                    }
                    @Override protected void processOperator(org.apache.pdfbox.contentstream.operator.Operator operator,
                            java.util.List<org.apache.pdfbox.cos.COSBase> operands) throws IOException {
                        budget.check();
                        if (org.apache.pdfbox.contentstream.operator.OperatorName.SET_FONT_AND_SIZE.equals(operator.getName())
                                && (operands.size() != 2 || !(operands.getFirst() instanceof org.apache.pdfbox.cos.COSName font)
                                || !(operands.getLast() instanceof org.apache.pdfbox.cos.COSNumber)
                                || getResources().getFont(font) == null))
                            throw new ProcessingFailure(ProcessingFailure.Kind.INVALID);
                        super.processOperator(operator, operands);
                    }
                    @Override protected void operatorException(org.apache.pdfbox.contentstream.operator.Operator operator,
                            java.util.List<org.apache.pdfbox.cos.COSBase> operands, IOException exception) throws IOException {
                        // PDFBox normally tolerates some damaged operators; ingestion must never publish partial text.
                        throw exception;
                    }
                };
                stripper.setSortByPosition(false);
                stripper.setLineSeparator(DocumentContent.LINE_SEPARATOR);
                stripper.setPageStart("");
                stripper.setPageEnd(DocumentContent.LINE_SEPARATOR);
                stripper.setParagraphStart("");
                stripper.setParagraphEnd("");
                for (int page = 1; page <= count; page++) {
                    budget.check();
                    staged.setLength(0);
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    stripper.writeText(document, new Writer() {
                        @Override public void write(char[] chars, int offset, int length) {
                            budget.check();
                            if (length > budget.limits().normalizedBytes() - text.length() - staged.length())
                                throw new ProcessingFailure(ProcessingFailure.Kind.LIMIT);
                            staged.append(chars, offset, length);
                        }
                        @Override public void flush() { }
                        @Override public void close() { }
                    });
                    text.append(DocumentContent.normalize(staged.toString()));
                    // PDFBox skips pages without content streams; retain their ordinal with an empty span.
                    ends.add(text.length());
                }
                budget.check();
                if (text.toString().isBlank()) throw new ProcessingFailure(ProcessingFailure.Kind.NO_TEXT);
                var result = DocumentContent.processed(original, PDF, text.toString(),
                        new ProcessingDetails(ID, Version.getVersion(), OPTIONS
                                + ";normalizer=" + ProcessingDetails.TEXT_PROCESSOR + "/" + ProcessingDetails.TEXT_VERSION
                                + ";indent=" + stripper.getIndentThreshold() + ";drop=" + stripper.getDropThreshold()
                                + ";java=" + Runtime.version(), ends));
                budget.limits().validate(result);
                return result;
            }
        } catch (ProcessingFailure failure) { throw failure;
        } catch (IOException | RuntimeException exception) {
            throw new ProcessingFailure(ProcessingFailure.Kind.INVALID);
        }
    }
}
