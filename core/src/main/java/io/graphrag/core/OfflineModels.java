package io.graphrag.core;

import static io.graphrag.core.Domain.*;
import static io.graphrag.core.Ports.*;

import java.util.*;
import java.util.regex.*;

/** Deterministic co-mention extraction and extractive answers, never a simulated LLM. */
public final class OfflineModels implements LanguageModel, Embeddings {
  private static final Pattern NAMES = Pattern.compile("\\b[A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,3}\\b");
  private static final Set<String> IGNORE =
      Set.of(
          "The",
          "A",
          "An",
          "At",
          "In",
          "On",
          "He",
          "She",
          "They",
          "His",
          "Her",
          "It",
          "This",
          "After",
          "Before",
          "Meanwhile",
          "Later",
          "Together");

  public boolean offline() {
    return true;
  }

  public List<EntityDraft> entities(String text) {
    Map<String, EntityDraft> out = new TreeMap<>();
    Matcher m = NAMES.matcher(text);
    while (m.find()) {
      String n = m.group();
      if (IGNORE.contains(n)) continue;
      String sentence =
          Arrays.stream(text.split("(?<=[.!?])\\s+"))
              .filter(x -> x.contains(n))
              .findFirst()
              .orElse(n);
      String type =
          n.matches(".*(Bank|Police|Society|Company|Scotland Yard).*")
              ? "Organization"
              : n.matches(".*(Street|London|Dartmoor|Valley|Hall|Lodge|Station|Baker).*")
                  ? "Place"
                  : "Person";
      out.put(Text.normalize(n), new EntityDraft(n, type, sentence));
    }
    return List.copyOf(out.values());
  }

  public List<RelationDraft> relationships(String text, List<EntityDraft> entities) {
    Map<String, RelationDraft> out = new TreeMap<>();
    for (String sentence : text.split("(?<=[.!?])\\s+")) {
      var names = entities.stream().filter(e -> sentence.contains(e.name())).toList();
      for (int i = 0; i < names.size(); i++)
        for (int j = i + 1; j < names.size(); j++) {
          var a = names.get(i);
          var b = names.get(j);
          out.put(
              a.name() + "\u0000" + b.name(),
              new RelationDraft(a.name(), b.name(), "CO_OCCURS", sentence, 1));
        }
    }
    return List.copyOf(out.values());
  }

  public Summary summarize(List<Entity> es, List<Relationship> rs) {
    String title =
        es.stream().limit(3).map(Entity::name).reduce((a, b) -> a + " · " + b).orElse("Community");
    String body =
        rs.stream()
            .map(Relationship::description)
            .distinct()
            .limit(8)
            .reduce((a, b) -> a + " " + b)
            .orElseGet(
                () ->
                    es.stream().map(Entity::description).reduce((a, b) -> a + " " + b).orElse(""));
    return new Summary(title, body);
  }

  public List<String> followups(String q, List<Community> cs, int limit) {
    return cs.stream().limit(limit).map(c -> q + " — related to " + c.title()).toList();
  }

  public String answer(String q, List<Evidence> context) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < context.size(); i++) {
      var e = context.get(i);
      String best =
          Arrays.stream(e.text().split("(?<=[.!?])\\s+"))
              .max(Comparator.comparingDouble(s -> Text.keyword(q, s)))
              .orElse("");
      if (Text.keyword(q, best) > 0) {
        if (!out.isEmpty()) out.append("\n\n");
        out.append(best).append(" [").append(i + 1).append("]");
        if (out.length() > 1600) break;
      }
    }
    return out.isEmpty() ? "NO_ANSWER" : out.toString();
  }

  public List<Double> embed(String text) {
    double[] a = new double[256];
    for (String token : Text.tokens(text)) a[Math.floorMod(token.hashCode(), a.length)] += 1;
    double norm = Math.sqrt(Arrays.stream(a).map(x -> x * x).sum());
    List<Double> v = new ArrayList<>();
    for (double x : a) v.add(norm == 0 ? 0 : x / norm);
    return List.copyOf(v);
  }
}
