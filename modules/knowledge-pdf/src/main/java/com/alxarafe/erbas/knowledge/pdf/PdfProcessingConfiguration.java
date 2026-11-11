package com.alxarafe.erbas.knowledge.pdf;

import com.alxarafe.erbas.knowledge.storage.infrastructure.KnowledgeConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Explicit adapter entry point listed only by the optional PDF artifact. */
@AutoConfiguration
public class PdfProcessingConfiguration {
    public static final String ENABLED_PROPERTY = KnowledgeConfiguration.PROCESSING_PREFIX + "pdf.enabled";
    @Bean
    @ConditionalOnProperty(name = ENABLED_PROPERTY, matchIfMissing = true)
    PdfDocumentProcessor pdfDocumentProcessor() { return new PdfDocumentProcessor(); }
}
