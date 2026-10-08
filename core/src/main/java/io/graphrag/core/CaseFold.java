package io.graphrag.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Unicode 16 full default (non-Turkic) case folding, using the official data table. */
final class CaseFold {
  private static final Map<Integer, String> TABLE = load();

  private CaseFold() {}

  private static Map<Integer, String> load() {
    Map<Integer, String> table = new HashMap<>();
    try (var stream = CaseFold.class.getResourceAsStream("/unicode/CaseFolding-16.0.0.txt")) {
      if (stream == null) throw new IllegalStateException("Missing Unicode case-folding data");
      try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        for (String line; (line = reader.readLine()) != null; ) {
          String body = line.split("#", 2)[0].trim();
          if (body.isEmpty()) continue;
          String[] fields = body.split(";");
          String status = fields[1].trim();
          if (!status.equals("C") && !status.equals("F")) continue;
          StringBuilder mapped = new StringBuilder();
          for (String hex : fields[2].trim().split(" "))
            mapped.appendCodePoint(Integer.parseInt(hex, 16));
          table.put(Integer.parseInt(fields[0].trim(), 16), mapped.toString());
        }
      }
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
    return Map.copyOf(table);
  }

  static String apply(String input) {
    StringBuilder out = new StringBuilder();
    input
        .codePoints()
        .forEach(
            cp -> {
              String mapped = TABLE.get(cp);
              if (mapped == null) out.appendCodePoint(cp);
              else out.append(mapped);
            });
    return out.toString();
  }
}
