package io.graphrag.core;

import static io.graphrag.core.Domain.*;

import java.util.*;
import java.util.regex.*;

public final class Answers {
  private final Retrieval retrieval;
  private final Ports.LanguageModel model;
  private final Map<String, Trace> traces = Collections.synchronizedMap(new LinkedHashMap<>());

  public Answers(Retrieval retrieval, Ports.LanguageModel model) {
    this.retrieval = retrieval;
    this.model = model;
  }

  public Trace trace(String id) {
    Trace t = traces.get(id);
    if (t == null)
      throw new NoSuchElementException("Trace has expired or was removed by a restart");
    return t;
  }

  public Answer query(String corpusId, String question, Mode mode) {
    Context ctx = retrieval.retrieve(corpusId, question, mode);
    long start = System.nanoTime();
    String text = ctx.evidence().isEmpty() ? "NO_ANSWER" : model.answer(question, ctx.evidence());
    long generation = (System.nanoTime() - start) / 1_000_000 + ctx.generationMs();
    List<Citation> citations = new ArrayList<>();
    boolean noAnswer = text == null || text.isBlank() || text.strip().equals("NO_ANSWER");
    if (!noAnswer) {
      Matcher m = Pattern.compile("\\[(\\d+)\\]").matcher(text);
      Map<Integer, Integer> numbering = new LinkedHashMap<>();
      StringBuffer cleaned = new StringBuffer();
      while (m.find()) {
        int n;
        try {
          n = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Model returned an invalid citation");
        }
        if (n < 1 || n > ctx.evidence().size())
          throw new IllegalArgumentException("Model cited evidence outside the retrieved context");
        int next = numbering.computeIfAbsent(n, k -> numbering.size() + 1);
        m.appendReplacement(cleaned, Matcher.quoteReplacement("[" + next + "]"));
      }
      m.appendTail(cleaned);
      if (numbering.isEmpty()) noAnswer = true;
      else {
        text = cleaned.toString();
        numbering.forEach(
            (original, n) -> {
              var e = ctx.evidence().get(original - 1);
              citations.add(
                  new Citation(
                      n,
                      e.id(),
                      e.documentName(),
                      e.text().substring(0, Math.min(600, e.text().length())),
                      e.kind()));
            });
      }
    }
    if (noAnswer) {
      text = "The retrieved sources do not provide enough evidence to answer this question.";
      citations.clear();
    }
    List<Step> steps = new ArrayList<>(ctx.trace().steps());
    steps.add(
        new Step(
            "step-" + (steps.size() + 1),
            "ANSWER_SYNTHESIS",
            noAnswer
                ? "Insufficient evidence — no answer"
                : "Synthesize an answer from " + ctx.evidence().size() + " numbered sources",
            ctx.evidence().stream().map(Evidence::id).toList(),
            null,
            Map.of("citations", citations.size())));
    Trace trace = new Trace(ctx.trace().id(), corpusId, mode, question, List.copyOf(steps));
    synchronized (traces) {
      traces.put(trace.id(), trace);
      while (traces.size() > 1000) traces.remove(traces.keySet().iterator().next());
    }
    return new Answer(
        mode,
        question,
        text,
        List.copyOf(citations),
        trace.id(),
        noAnswer,
        new Timing(ctx.retrievalMs(), ctx.embeddingMs(), generation),
        ctx.evidence().size(),
        (int) ctx.evidence().stream().map(Evidence::documentName).distinct().count(),
        ctx.rankedChunks());
  }

  public Comparison compare(String corpus, String q, List<Mode> modes) {
    Map<Mode, Answer> results = new LinkedHashMap<>();
    Map<Mode, String> errors = new LinkedHashMap<>();
    for (Mode m : modes)
      try {
        results.put(m, query(corpus, q, m));
      } catch (Exception e) {
        errors.put(m, Ingestion.safeReason(e));
      }
    Map<String, EvidenceState> matrix = new LinkedHashMap<>();
    for (var en : results.entrySet()) {
      Mode mode = en.getKey();
      Answer a = en.getValue();
      Set<String> cited = new HashSet<>();
      a.citations().forEach(c -> cited.add(c.sourceId()));
      for (Step st : trace(a.traceId()).steps()) {
        if (!st.kind().equals("SOURCE_PASSAGE_RETRIEVED")
            && !st.kind().equals("VECTOR_CHUNK_SELECTED")) continue;
        for (String id : st.targets()) {
          EvidenceState prev = matrix.get(id);
          Map<Mode, String> states =
              prev == null ? new LinkedHashMap<>() : new LinkedHashMap<>(prev.states());
          states.put(
              mode,
              cited.contains(id)
                  ? "CITED"
                  : Boolean.FALSE.equals(st.metadata().get("inContext"))
                      ? "BELOW_CUTOFF"
                      : "IN_CONTEXT");
          String name =
              String.valueOf(
                  st.metadata()
                      .getOrDefault("documentName", prev == null ? "Source" : prev.documentName()));
          String excerpt =
              a.citations().stream()
                  .filter(c -> c.sourceId().equals(id))
                  .map(Citation::excerpt)
                  .findFirst()
                  .orElse(prev == null ? "" : prev.excerpt());
          matrix.put(id, new EvidenceState(id, name, excerpt, states));
        }
      }
    }
    List<EvidenceState> rows = new ArrayList<>();
    for (EvidenceState e : matrix.values()) {
      Map<Mode, String> states = new LinkedHashMap<>(e.states());
      modes.forEach(m -> states.putIfAbsent(m, "NOT_RETRIEVED"));
      rows.add(new EvidenceState(e.id(), e.documentName(), e.excerpt(), Map.copyOf(states)));
    }
    Set<String> union = new HashSet<>(), intersection = null;
    for (Answer a : results.values()) {
      Set<String> ids =
          new HashSet<>(
              trace(a.traceId()).steps().stream()
                  .filter(
                      s ->
                          s.kind().equals("SOURCE_PASSAGE_RETRIEVED")
                              || s.kind().equals("VECTOR_CHUNK_SELECTED")
                                  && !Boolean.FALSE.equals(s.metadata().get("inContext")))
                  .flatMap(s -> s.targets().stream())
                  .toList());
      union.addAll(ids);
      if (intersection == null) intersection = ids;
      else intersection.retainAll(ids);
    }
    double overlap = union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    String explanation =
        "Measured source-ID overlap: "
            + Math.round(overlap * 100)
            + "%. Graph passages and vector chunks have different boundaries; use the document"
            + " coverage and evidence matrix to inspect those differences. These measurements do"
            + " not establish answer correctness.";
    return new Comparison(
        Map.copyOf(results), Map.copyOf(errors), List.copyOf(rows), overlap, explanation);
  }
}
