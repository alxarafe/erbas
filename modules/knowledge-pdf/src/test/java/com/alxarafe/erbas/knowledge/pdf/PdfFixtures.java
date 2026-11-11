package com.alxarafe.erbas.knowledge.pdf;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

final class PdfFixtures {
    static byte[] documentWithActualText(String actualText) throws Exception {
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                var properties = new org.apache.pdfbox.cos.COSDictionary();
                properties.setString(org.apache.pdfbox.cos.COSName.ACTUAL_TEXT, actualText);
                stream.beginMarkedContent(org.apache.pdfbox.cos.COSName.getPDFName("Fixture"),
                        org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(properties));
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("Cafe");
                stream.endText();
                stream.endMarkedContent();
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
    static byte[] brokenSecondPage() throws Exception {
        try (var document = org.apache.pdfbox.Loader.loadPDF(document("Complete first page", "Second"));
                var bytes = new ByteArrayOutputStream()) {
            var stream = new org.apache.pdfbox.pdmodel.common.PDStream(document);
            try (var output = stream.createOutputStream()) {
                output.write("BT 10 Td ET".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            document.getPage(1).setContents(stream);
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
    static byte[] document(String... pages) throws Exception {
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pages) {
                var page = new PDPage();
                document.addPage(page);
                if (!text.isEmpty()) try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
