package io.graphrag.core;

import static io.graphrag.core.Domain.*;
import static io.graphrag.core.Ports.*;

import java.util.*;

public final class Retrieval {
  private final GraphRead graph;
  private final VectorStorage vectors;
  private final Embeddings embedder;
  private final LanguageModel model;
  private final Settings cfg;

  public Retrieval(
      GraphRead graph,
      VectorStorage vectors,
      Embeddings embedder,
      LanguageModel model,
      Settings cfg) {
    this.graph = graph;
    this.vectors = vectors;
    this.embedder = embedder;
    this.model = model;
    this.cfg = cfg;
  }

  private final class Run {
    final Snapshot data;
    final Mode mode;
    final String question;
    final List<Step> steps = new ArrayList<>();
    final LinkedHashMap<String, Evidence> evidence = new LinkedHashMap<>();
    final List<RankedChunk> ranked = new ArrayList<>();
    long embeddingNs = 0, generationNs = 0;

    Run(Snapshot d, Mode m, String q) {
      data = d;
      mode = m;
      question = q;
    }

    String step(
        String kind, String label, List<String> targets, String parent, Map<String, Object> meta) {
      String id = "step-" + (steps.size() + 1);
      steps.add(new Step(id, kind, label, targets, parent, meta));
      return id;
    }

    List<Double> embed(String q) {
      long t = System.nanoTime();
      try {
        return embedder.embed(q);
      } finally {
        embeddingNs += System.nanoTime() - t;
      }
    }

    void passage(String pid, String parent) {
      if (evidence.containsKey(pid)) return;
      data.passages().stream()
          .filter(p -> p.id().equals(pid))
          .findFirst()
          .ifPresent(
              p -> {
                evidence.put(pid, new Evidence(pid, p.documentName(), p.text(), "passage"));
                step(
                    "SOURCE_PASSAGE_RETRIEVED",
                    "Read " + p.documentName() + " · passage " + (p.ordinal() + 1),
                    List.of(pid),
                    parent,
                    Map.of(
                        "documentName",
                        p.documentName(),
                        "location",
                        p.location() == null ? "" : p.location()));
              });
    }

    void local(String q, String parent, Matching matching) {
      List<Double> query =
          (matching == Matching.SEMANTIC || matching == Matching.HYBRID)
                  && !data.entityEmbeddings().isEmpty()
              ? embed(q)
              : List.of();
      Map<String, Double> scores = new HashMap<>();
      for (Entity e : data.entities()) {
        double kw = Text.keyword(q, e.name() + " " + e.description());
        double sem =
            query.isEmpty()
                ? 0
                : Text.cosine(query, data.entityEmbeddings().getOrDefault(e.id(), List.of()));
        double score =
            switch (matching) {
              case KEYWORD -> kw;
              case SEMANTIC -> sem;
              case IDENTIFIER ->
                  q.contains(e.id())
                      ? 1
                      : Text.normalize(q).contains(Text.normalize(e.name())) ? 0.9 : 0;
              case HYBRID -> 0.5 * kw + 0.5 * sem;
            };
        scores.put(e.id(), score);
      }
      List<Entity> seeds =
          data.entities().stream()
              .filter(e -> scores.get(e.id()) > 0)
              .sorted(
                  Comparator.<Entity>comparingDouble(e -> scores.get(e.id()))
                      .reversed()
                      .thenComparing(Entity::id))
              .limit(cfg.seeds())
              .toList();
      Set<String> ids = new HashSet<>();
      for (Entity e : seeds) {
        ids.add(e.id());
        step(
            "ENTITY_SELECTED",
            "Select seed: " + e.name(),
            List.of(e.id()),
            parent,
            Map.of("score", scores.get(e.id()), "depth", 0));
      }
      List<Relationship> rels =
          data.relationships().stream()
              .filter(r -> ids.contains(r.source()) || ids.contains(r.target()))
              .sorted(
                  Comparator.comparingDouble(Relationship::weight)
                      .reversed()
                      .thenComparing(Relationship::id))
              .limit(cfg.relationships())
              .toList();
      Set<String> candidates = new TreeSet<>();
      seeds.forEach(e -> candidates.addAll(e.passageIds()));
      for (Relationship r : rels) {
        step(
            "RELATIONSHIP_TRAVERSED",
            "Traverse " + r.type(),
            List.of(r.id(), r.source(), r.target()),
            parent,
            Map.of("weight", r.weight(), "depth", 1));
        candidates.addAll(r.passageIds());
      }
      data.passages().stream()
          .filter(p -> candidates.contains(p.id()))
          .sorted(
              Comparator.<Passage>comparingDouble(p -> Text.keyword(q, p.text()))
                  .reversed()
                  .thenComparing(Passage::id))
          .limit(cfg.passages())
          .forEach(p -> passage(p.id(), parent));
    }

    List<Community> communities(String q) {
      if (data.communities().isEmpty()) {
        step(
            "EMPTY", "No communities with at least three members exist", List.of(), null, Map.of());
        return List.of();
      }
      List<Double> query = model.offline() ? List.of() : embed(q);
      Map<String, Double> scores = new HashMap<>();
      for (Community c : data.communities())
        scores.put(
            c.id(),
            query.isEmpty()
                ? Text.keyword(q, c.title() + " " + c.summary())
                : Text.cosine(query, c.embedding()));
      List<Community> cs =
          data.communities().stream()
              .filter(c -> scores.get(c.id()) > 0)
              .sorted(
                  Comparator.<Community>comparingDouble(c -> scores.get(c.id()))
                      .reversed()
                      .thenComparing(Community::id))
              .limit(cfg.communities())
              .toList();
      for (Community c : cs) {
        String parent =
            step(
                "COMMUNITY_SELECTED",
                "Select community: " + c.title(),
                List.of(c.id()),
                null,
                Map.of("score", scores.get(c.id())));
        Set<String> ps = new HashSet<>();
        data.entities().stream()
            .filter(e -> c.members().contains(e.id()))
            .forEach(e -> ps.addAll(e.passageIds()));
        data.passages().stream()
            .filter(p -> ps.contains(p.id()))
            .sorted(
                Comparator.<Passage>comparingDouble(p -> Text.keyword(q, p.text()))
                    .reversed()
                    .thenComparing(Passage::id))
            .limit(cfg.passagesPerCommunity())
            .forEach(p -> passage(p.id(), parent));
      }
      return cs;
    }

    void vector() {
      List<Double> q = embed(question);
      step(
          "VECTOR_QUERY_EMBEDDED",
          model.offline()
              ? "Hash query terms into a deterministic offline vector"
              : "Embed the question",
          List.of(),
          null,
          Map.of("dimensions", q.size()));
      var ordered =
          vectors.chunks(data.corpus().id()).stream()
              .map(c -> new RankedChunk(c, Text.cosine(q, c.embedding()), false))
              .sorted(
                  Comparator.comparingDouble(RankedChunk::score)
                      .reversed()
                      .thenComparing(c -> c.chunk().id()))
              .limit(cfg.vectorInspect())
              .toList();
      for (int i = 0; i < ordered.size(); i++) {
        var c = ordered.get(i);
        boolean included = i < cfg.vectorContext() && c.score() > 0;
        ranked.add(new RankedChunk(c.chunk(), c.score(), included));
        step(
            "VECTOR_CHUNK_SELECTED",
            "Rank "
                + (i + 1)
                + ": "
                + c.chunk().documentName()
                + (included ? " · in context" : " · below cutoff"),
            List.of(c.chunk().id()),
            null,
            Map.of("score", c.score(), "rank", i + 1, "inContext", included));
        if (included)
          evidence.put(
              c.chunk().id(),
              new Evidence(c.chunk().id(), c.chunk().documentName(), c.chunk().text(), "chunk"));
      }
    }
  }

  public Context retrieve(String corpusId, String question, Mode mode, Matching matching) {
    if (question == null || question.isBlank() || question.length() > 4000)
      throw new IllegalArgumentException("Question must contain 1–4000 characters");
    Snapshot s = graph.read(corpusId);
    if (s.corpus().status() != Status.READY)
      throw new IllegalStateException("Corpus must be READY before querying");
    long start = System.nanoTime();
    Run r = new Run(s, mode, question);
    switch (mode) {
      case LOCAL -> r.local(question, null, matching);
      case GLOBAL -> r.communities(question);
      case DRIFT -> {
        var cs = r.communities(question);
        if (!cs.isEmpty()) {
          long t = System.nanoTime();
          List<String> qs;
          try {
            qs = model.followups(question, cs, cfg.followups());
          } finally {
            r.generationNs += System.nanoTime() - t;
          }
          for (String q :
              qs.stream().filter(x -> x != null && !x.isBlank()).limit(cfg.followups()).toList()) {
            String communityParent =
                r.steps.stream()
                    .filter(st -> st.kind().equals("COMMUNITY_SELECTED"))
                    .skip(Math.min(qs.indexOf(q), cs.size() - 1))
                    .map(Step::id)
                    .findFirst()
                    .orElse(null);
            String parent =
                r.step(
                    "FOLLOW_UP_QUESTION_CREATED",
                    q,
                    List.of(),
                    communityParent,
                    Map.of("branch", q));
            r.local(q, parent, matching);
          }
        }
      }
      case VECTOR -> r.vector();
    }
    long elapsed = System.nanoTime() - start;
    Trace trace =
        new Trace(UUID.randomUUID().toString(), corpusId, mode, question, List.copyOf(r.steps));
    return new Context(
        List.copyOf(r.evidence.values()),
        trace,
        List.copyOf(r.ranked),
        Math.max(0, (elapsed - r.embeddingNs - r.generationNs) / 1_000_000),
        r.embeddingNs / 1_000_000,
        r.generationNs / 1_000_000);
  }

  public Context retrieve(String corpusId, String question, Mode mode) {
    return retrieve(corpusId, question, mode, model.offline() ? Matching.KEYWORD : Matching.HYBRID);
  }
}
