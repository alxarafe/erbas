package com.alxarafe.erbas.knowledge.storage.application;

import java.util.Set;
import com.alxarafe.erbas.knowledge.storage.domain.DocumentContent;
import com.alxarafe.erbas.knowledge.storage.domain.ProcessingDetails;

public final class TextDocumentProcessor implements DocumentProcessor {
    public String id() { return ProcessingDetails.TEXT_PROCESSOR; }
    public Set<String> contentTypes() { return Set.of(DocumentContent.TXT, DocumentContent.MARKDOWN); }
    public DocumentContent process(byte[] original, String contentType, ProcessingBudget budget) {
        budget.check();
        return new DocumentContent(original, contentType);
    }
}
