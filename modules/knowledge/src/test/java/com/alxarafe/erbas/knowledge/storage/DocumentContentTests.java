package com.alxarafe.erbas.knowledge.storage;

import java.nio.charset.StandardCharsets;
import com.alxarafe.erbas.knowledge.storage.domain.DocumentContent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DocumentContentTests {
    @Test void preservesOriginalAndNormalizesOnlyBomNewlinesAndUnicode() {
        byte[] bytes = "\uFEFF# Cafe\u0301\r\n  text\r".getBytes(StandardCharsets.UTF_8);
        var content = new DocumentContent(bytes, DocumentContent.MARKDOWN);
        assertThat(content.text()).isEqualTo("# Café\n  text\n");
        assertThat(content.original()).isEqualTo(bytes);
        bytes[0] = 0;
        byte[] copy = content.original();
        copy[0] = 0;
        assertThat(content.original()[0]).isNotZero();
        assertThat(content.sha256()).hasSize(64);
    }
    @Test void rejectsMalformedUtf8UnsupportedTypesAndNul() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentContent(new byte[]{(byte) 0xff}, DocumentContent.TXT));
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentContent(new byte[0], "application/pdf"));
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentContent(new byte[]{0}, DocumentContent.TXT));
    }
}
