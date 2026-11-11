package com.alxarafe.erbas.knowledge.storage.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import com.alxarafe.erbas.knowledge.storage.domain.DocumentContent;

/** Internal ingestion, independent of storage placement and authorization. Caller owns the stream. */
public final class DocumentProcessingService {
    private static final int INPUT_BUFFER_BYTES = 8192;
    private final ProcessingLimits limits;
    private final Map<String, DocumentProcessor> processors;
    public DocumentProcessingService(ProcessingLimits limits, Collection<DocumentProcessor> processors) {
        this.limits = limits;
        var registered = new HashMap<String, DocumentProcessor>();
        var ids = new java.util.HashSet<String>();
        for (var processor : processors) {
            if (processor.id() == null || processor.id().isBlank() || !ids.add(processor.id()))
                throw new IllegalArgumentException("Invalid or duplicate document processor");
            for (var type : processor.contentTypes())
                if (registered.putIfAbsent(type, processor) != null)
                    throw new IllegalArgumentException("Ambiguous document content type");
        }
        this.processors = Map.copyOf(registered);
    }
    public DocumentContent process(InputStream input, String contentType) {
        var processor = contentType == null ? null : processors.get(contentType);
        if (processor == null) throw new ProcessingFailure(ProcessingFailure.Kind.UNSUPPORTED);
        var budget = new ProcessingBudget(limits);
        try {
            var staged = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[Math.min(INPUT_BUFFER_BYTES, limits.originalBytes() + 1)];
            while (true) {
                budget.check();
                int read = input.read(buffer, 0, Math.min(buffer.length, limits.originalBytes() - staged.size() + 1));
                if (read < 0) break;
                if (read > limits.originalBytes() - staged.size()) throw new ProcessingFailure(ProcessingFailure.Kind.LIMIT);
                staged.write(buffer, 0, read);
            }
            byte[] original = staged.toByteArray();
            budget.check();
            var content = processor.process(original.clone(), contentType, budget);
            budget.check();
            if (!Arrays.equals(original, content.original()) || !contentType.equals(content.contentType())
                    || !processor.id().equals(content.processing().processor()))
                throw new ProcessingFailure(ProcessingFailure.Kind.INVALID);
            limits.validate(content);
            budget.check();
            return content;
        } catch (ProcessingFailure failure) { throw failure;
        } catch (IOException | RuntimeException exception) {
            throw new ProcessingFailure(ProcessingFailure.Kind.INVALID);
        }
    }
}
