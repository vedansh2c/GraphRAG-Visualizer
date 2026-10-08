package io.graphrag.document;

import static org.junit.jupiter.api.Assertions.*;

import io.graphrag.core.Domain.Input;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;

class DocumentsTest {
  @Test
  void textAndInvalidFiles() {
    var p = new Documents();
    assertEquals("Hello", p.parse(new Input("hi.txt", "text/plain", "Hello".getBytes())).text());
    assertThrows(
        IllegalArgumentException.class, () -> p.parse(new Input("x.exe", "", new byte[] {1})));
    assertThrows(
        IllegalArgumentException.class, () -> p.parse(new Input("x.pdf", "", new byte[] {1})));
    assertThrows(
        IllegalArgumentException.class,
        () -> p.parse(new Input("x.txt", "", new byte[] {(byte) 0xff})));
  }

  @Test
  void pdfExtractsProvenance() throws Exception {
    try (var pdf = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      var page = new PDPage();
      pdf.addPage(page);
      try (var content = new PDPageContentStream(pdf, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(50, 700);
        content.showText("Sherlock Holmes met John Watson.");
        content.endText();
      }
      pdf.save(out);
      var parsed =
          new Documents().parse(new Input("case.pdf", "application/pdf", out.toByteArray()));
      assertTrue(parsed.text().contains("Sherlock Holmes"));
      assertTrue(parsed.text().contains("[Page 1]"));
    }
  }
}
