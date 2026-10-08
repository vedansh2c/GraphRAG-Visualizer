package io.graphrag.document;

import io.graphrag.core.Domain.*;
import io.graphrag.core.Ports.DocumentParser;
import java.nio.*;
import java.nio.charset.*;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

public final class Documents implements DocumentParser {
  public Parsed parse(Input input) {
    String name = input.name().toLowerCase(Locale.ROOT);
    try {
      if (name.endsWith(".txt")) {
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input.bytes()))
                .toString();
        return new Parsed(text, "UTF-8 text");
      }
      if (name.endsWith(".pdf")) {
        try (var pdf = Loader.loadPDF(input.bytes())) {
          if (pdf.isEncrypted() && !pdf.getCurrentAccessPermission().canExtractContent())
            throw new IllegalArgumentException(
                "PDF does not permit text extraction: " + input.name());
          StringBuilder out = new StringBuilder();
          boolean hasText = false;
          for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String pageText = stripper.getText(pdf);
            hasText |= !pageText.isBlank();
            out.append("\n[Page ").append(page).append("]\n").append(pageText);
          }
          return new Parsed(hasText ? out.toString().trim() : "", "PDF page markers in text");
        }
      }
      throw new IllegalArgumentException(
          "Unsupported file: " + input.name() + ". Upload .txt or .pdf files.");
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Cannot read "
              + input.name()
              + ". Use a valid UTF-8 text file or an unlocked, readable PDF.");
    }
  }
}
