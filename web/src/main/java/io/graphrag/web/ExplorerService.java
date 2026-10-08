package io.graphrag.web;

import io.graphrag.core.*;
import io.graphrag.core.Domain.*;
import io.graphrag.core.Ports.*;
import io.graphrag.document.Documents;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public final class ExplorerService {
  public final Store store;
  private final CommunityDetector detector;
  private final Settings cfg;
  private final OfflineModels offline;
  private final Configuration.ModelSet live;
  private final ProgressEvents events;
  private final Map<String, Answers> answers = new ConcurrentHashMap<>();
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
  public final int maxFiles;
  public final long maxBytes;

  public ExplorerService(
      Store store,
      CommunityDetector detector,
      Settings cfg,
      OfflineModels offline,
      Configuration.ModelSet live,
      ProgressEvents events,
      Environment e) {
    this.store = store;
    this.detector = detector;
    this.cfg = cfg;
    this.offline = offline;
    this.live = live;
    this.events = events;
    maxFiles = e.getProperty("explorer.max-files", Integer.class, 20);
    maxBytes = e.getProperty("explorer.max-total-bytes", Long.class, 40000000L);
  }

  @PostConstruct
  public void recover() {
    for (Corpus c : store.corpora())
      if (c.status() == Status.BUILDING)
        store.save(
            store
                .read(c.id())
                .status(
                    Status.FAILED,
                    "interrupted",
                    "Application stopped during ingestion. Original corpora remain available;"
                        + " upload these sources again to retry."));
  }

  @PreDestroy
  public void stop() {
    workers.shutdownNow();
  }

  private Ingestion ingestion(boolean forced) {
    return new Ingestion(
        store,
        new Documents(),
        forced ? offline : live.language(),
        forced ? offline : live.embeddings(),
        detector,
        cfg);
  }

  public Corpus create(String name, List<Input> files, boolean forceOffline) {
    if (name == null || name.isBlank() || name.length() > 120)
      throw new IllegalArgumentException("Corpus name must contain 1–120 characters");
    if (files.isEmpty() || files.size() > maxFiles)
      throw new IllegalArgumentException("Upload between 1 and " + maxFiles + " files");
    long bytes = 0;
    for (Input file : files) {
      if (file.bytes().length == 0)
        throw new IllegalArgumentException("File is empty: " + file.name());
      String n = file.name().toLowerCase(Locale.ROOT);
      if (!n.endsWith(".txt") && !n.endsWith(".pdf"))
        throw new IllegalArgumentException(
            "Unsupported file: " + file.name() + ". Upload .txt or .pdf files.");
      bytes += file.bytes().length;
    }
    if (bytes > maxBytes)
      throw new IllegalArgumentException(
          "Upload exceeds the configured " + maxBytes + " byte limit");
    Ingestion pipeline = ingestion(forceOffline);
    Corpus c = pipeline.create(name);
    store.activate(c.id());
    try {
      workers.execute(() -> pipeline.process(c.id(), files, p -> events.publish(c.id(), p)));
    } catch (RejectedExecutionException e) {
      store.save(
          store
              .read(c.id())
              .status(
                  Status.FAILED,
                  "queue",
                  "Ingestion queue is full. Wait for another corpus to finish, then retry."));
      throw new BusyException();
    }
    return c;
  }

  public Corpus demo(boolean offline) {
    List<Input> files = new ArrayList<>();
    for (String file : List.of("bank.txt", "moor.txt", "scandal.txt"))
      try (var in = getClass().getResourceAsStream("/demo/" + file)) {
        if (in == null) throw new IllegalStateException("Missing bundled dataset");
        files.add(new Input(file, "text/plain", in.readAllBytes()));
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
      }
    return create(offline ? "Holmes cases · offline" : "Holmes cases", files, offline);
  }

  private Answers engine(String corpus) {
    Corpus c = store.read(corpus).corpus();
    boolean deterministic = c.mode().equals("DETERMINISTIC");
    String key = deterministic ? "offline" : "live";
    return answers.computeIfAbsent(
        key,
        k -> {
          var language = deterministic ? offline : live.language();
          var embeddings = deterministic ? offline : live.embeddings();
          if (!deterministic && language.offline())
            throw new IllegalStateException(
                "This live corpus needs its model credentials restored");
          return new Answers(new Retrieval(store, store, embeddings, language, cfg), language);
        });
  }

  public Answer query(String id, String q, Mode m) {
    return engine(id).query(id, q, m);
  }

  public Comparison compare(String id, String q, List<Mode> modes) {
    Snapshot s = store.read(id);
    if (s.corpus().status() != Status.READY)
      throw new IllegalStateException("Corpus must be READY before comparing");
    if (q == null || q.isBlank() || q.length() > 4000)
      throw new IllegalArgumentException("Question must contain 1–4000 characters");
    Comparison comparison = engine(id).compare(id, q, modes);
    Map<String, EvidenceState> rows = new LinkedHashMap<>();
    for (Passage p : s.passages()) {
      Map<Mode, String> states = new LinkedHashMap<>();
      modes.forEach(m -> states.put(m, "NOT_RETRIEVED"));
      rows.put(
          p.id(),
          new EvidenceState(
              p.id(),
              p.documentName(),
              p.text().substring(0, Math.min(300, p.text().length())),
              states));
    }
    for (EvidenceState r : comparison.matrix()) {
      var passage = s.passages().stream().filter(p -> p.id().equals(r.id())).findFirst();
      var chunk = s.chunks().stream().filter(c -> c.id().equals(r.id())).findFirst();
      rows.put(
          r.id(),
          new EvidenceState(
              r.id(),
              passage
                  .map(Passage::documentName)
                  .orElseGet(() -> chunk.map(Chunk::documentName).orElse(r.documentName())),
              passage
                  .map(Passage::text)
                  .orElseGet(() -> chunk.map(Chunk::text).orElse(r.excerpt())),
              r.states()));
    }
    // Map actual vector chunks onto overlapping passages for an interpretable passage matrix.
    for (Passage p : s.passages()) {
      var row = rows.get(p.id());
      Map<Mode, String> states = new LinkedHashMap<>(row.states());
      for (Chunk ch : s.chunks())
        if (ch.documentId().equals(p.documentId()) && overlaps(p, ch)) {
          EvidenceState chunkRow = rows.get(ch.id());
          if (chunkRow != null && chunkRow.states().containsKey(Mode.VECTOR)) {
            String value = chunkRow.states().get(Mode.VECTOR);
            if (rank(value) > rank(states.get(Mode.VECTOR))) states.put(Mode.VECTOR, value);
          }
        }
      rows.put(p.id(), new EvidenceState(row.id(), row.documentName(), row.excerpt(), states));
    }
    Set<String> union = new HashSet<>(), intersection = null;
    for (Mode mode : comparison.results().keySet()) {
      Set<String> included = new HashSet<>();
      for (Passage passage : s.passages()) {
        String value = rows.get(passage.id()).states().get(mode);
        if ("CITED".equals(value) || "IN_CONTEXT".equals(value)) included.add(passage.id());
      }
      union.addAll(included);
      if (intersection == null) intersection = included;
      else intersection.retainAll(included);
    }
    double overlap = union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    String measured = "Measured passage coverage overlap: " + Math.round(overlap * 100) + "%. ";
    for (var result : comparison.results().entrySet())
      measured +=
          result.getKey()
              + ": "
              + result.getValue().contextSize()
              + " context items across "
              + result.getValue().documentCoverage()
              + " documents; "
              + result.getValue().timing().totalMs()
              + " ms. ";
    return new Comparison(
        comparison.results(),
        comparison.errors(),
        List.copyOf(rows.values()),
        overlap,
        measured
            + "Vector passage coverage maps overlapping chunk character ranges; exact chunk rows"
            + " are also shown. Coverage and timing do not establish answer correctness.");
  }

  private boolean overlaps(Passage p, Chunk c) {
    int ps = p.ordinal() * (cfg.passageSize() - cfg.overlap()),
        cs = c.ordinal() * (cfg.chunkSize() - Math.min(50, cfg.chunkSize() / 10));
    return ps < cs + c.text().length() && cs < ps + p.text().length();
  }

  private static int rank(String s) {
    return s == null
        ? 0
        : switch (s) {
          case "CITED" -> 3;
          case "IN_CONTEXT" -> 2;
          case "BELOW_CUTOFF" -> 1;
          default -> 0;
        };
  }

  public Trace trace(String id) {
    for (Answers a : answers.values())
      try {
        return a.trace(id);
      } catch (NoSuchElementException ignored) {
      }
    throw new NoSuchElementException(
        "Trace unavailable. Restarts clear traces; persisted corpora remain.");
  }

  public static final class BusyException extends RuntimeException {}
}
