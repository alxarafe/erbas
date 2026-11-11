package com.alxarafe.erbas.knowledge.storage.domain;

import java.util.List;

/** Immutable extraction recipe and cumulative page ends in Java UTF-16 text offsets. */
public record ProcessingDetails(String processor, String version, String options, List<Integer> pageEnds) {
    public static final String TEXT_PROCESSOR = "knowledge-text";
    public static final String TEXT_VERSION = "1";
    public static final String TEXT_OPTIONS = "utf8=strict;bom=remove-leading;newline=lf;unicode=nfc;nul=reject";
    public static final ProcessingDetails TEXT = new ProcessingDetails(TEXT_PROCESSOR, TEXT_VERSION, TEXT_OPTIONS, List.of());

    public ProcessingDetails {
        if (processor == null || processor.isBlank() || version == null || version.isBlank()
                || options == null || options.isBlank()) throw new IllegalArgumentException("Extraction recipe is required");
        pageEnds = List.copyOf(pageEnds);
        int previous = 0;
        for (int end : pageEnds) {
            if (end < previous) throw new IllegalArgumentException("Invalid page provenance");
            previous = end;
        }
    }
}
