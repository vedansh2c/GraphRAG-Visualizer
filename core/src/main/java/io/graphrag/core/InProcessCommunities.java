package io.graphrag.core;

import static io.graphrag.core.Domain.*;

import java.util.*;

/** Deterministic weighted label propagation fallback; deliberately not called Leiden. */
public final class InProcessCommunities implements Ports.CommunityDetector {
  public List<Set<String>> detect(String corpusId, List<Entity> entities, List<Relationship> rels) {
    Map<String, String> labels = new TreeMap<>();
    Map<String, Map<String, Double>> adj = new HashMap<>();
    for (Entity e : entities) {
      labels.put(e.id(), e.id());
      adj.put(e.id(), new HashMap<>());
    }
    for (Relationship r : rels) {
      adj.get(r.source()).merge(r.target(), r.weight(), Double::sum);
      adj.get(r.target()).merge(r.source(), r.weight(), Double::sum);
    }
    for (int pass = 0; pass < 30; pass++) {
      boolean changed = false;
      for (String id : labels.keySet()) {
        Map<String, Double> scores = new TreeMap<>();
        adj.get(id).forEach((n, w) -> scores.merge(labels.get(n), w, Double::sum));
        String best =
            scores.entrySet().stream()
                .sorted(
                    Map.Entry.<String, Double>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(labels.get(id));
        if (!labels.get(id).equals(best)) {
          labels.put(id, best);
          changed = true;
        }
      }
      if (!changed) break;
    }
    Map<String, Set<String>> groups = new TreeMap<>();
    labels.forEach((id, label) -> groups.computeIfAbsent(label, k -> new TreeSet<>()).add(id));
    return groups.values().stream().filter(s -> s.size() >= 3).map(Set::copyOf).toList();
  }
}
