package com.alxarafe.erbas.knowledge.pdf;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class PdfCompositionTests {
    @Test void theOptionalAdapterCanBeEnabledAndDisabledThroughComposition() {
        var runner = new ApplicationContextRunner().withUserConfiguration(PdfProcessingConfiguration.class);
        runner.run(context -> assertThat(context).hasSingleBean(PdfDocumentProcessor.class));
        runner.withPropertyValues(PdfProcessingConfiguration.ENABLED_PROPERTY + "=false")
                .run(context -> assertThat(context).doesNotHaveBean(PdfDocumentProcessor.class));
    }
}
