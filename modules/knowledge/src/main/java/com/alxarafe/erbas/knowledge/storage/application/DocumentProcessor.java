package com.alxarafe.erbas.knowledge.storage.application;

import java.util.Set;
import com.alxarafe.erbas.knowledge.storage.domain.DocumentContent;

/** Trusted synchronous adapter. Input is bounded; return only a complete immutable result. */
public interface DocumentProcessor {
    String id();
    Set<String> contentTypes();
    DocumentContent process(byte[] original, String contentType, ProcessingBudget budget);
}
