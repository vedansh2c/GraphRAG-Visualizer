package io.graphrag.neo4j;

import static org.junit.jupiter.api.Assertions.*;

import io.graphrag.contracts.StoreContract;
import io.graphrag.core.*;
import io.graphrag.core.Domain.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FileStoreTest extends StoreContract {
  @TempDir Path dir;

  protected Ports.Store store() {
    return new FileStore(dir);
  }

  @Test
  void allModesTracesGroundingAndRestart() {
    var store = new FileStore(dir);
    var offline = new OfflineModels();
    var parser =
        (Ports.DocumentParser)
            (i -> new Parsed(new String(i.bytes(), java.nio.charset.StandardCharsets.UTF_8), null));
    var cfg = Settings.defaults();
    var ingestion = new Ingestion(store, parser, offline, offline, new InProcessCommunities(), cfg);
    var c = ingestion.create("Demo");
    var events = new ArrayList<Progress>();
    ingestion.process(
        c.id(),
        List.of(
            new Input(
                "case.txt",
                "text/plain",
                "Sherlock Holmes met John Watson at Baker Street. John Watson helped Sherlock Holmes investigate London Bank. London Bank reported a theft at Baker Street."
                    .getBytes())),
        events::add);
    assertEquals(Status.READY, store.read(c.id()).corpus().status());
    assertTrue(events.stream().anyMatch(p -> p.stage().equals("extraction") && p.entities() > 0));
    assertTrue(events.getLast().terminal());
    var reopened = new FileStore(dir);
    assertFalse(reopened.read(c.id()).entities().isEmpty());
    assertFalse(reopened.read(c.id()).communities().isEmpty());
    var retrieval = new Retrieval(reopened, reopened, offline, offline, cfg);
    var answers = new Answers(retrieval, offline);
    for (Mode mode : Mode.values()) {
      var answer = answers.query(c.id(), "Sherlock Holmes", mode);
      assertFalse(answer.noAnswer(), mode.toString());
      var trace = answers.trace(answer.traceId());
      for (Citation citation : answer.citations())
        assertTrue(trace.steps().stream().anyMatch(s -> s.targets().contains(citation.sourceId())));
      if (mode == Mode.DRIFT)
        assertTrue(
            trace.steps().stream()
                .anyMatch(
                    s -> s.kind().equals("FOLLOW_UP_QUESTION_CREATED") && s.parentId() != null));
      if (mode == Mode.VECTOR) assertTrue(answer.rankedChunks().size() > 0);
    }
    assertTrue(answers.query(c.id(), "quantum neutrino", Mode.LOCAL).noAnswer());
    assertEquals(
        4, answers.compare(c.id(), "Sherlock Holmes", List.of(Mode.values())).results().size());
    ingestion.removeSource(c.id(), store.read(c.id()).documents().getFirst().id(), p -> {});
    assertTrue(store.read(c.id()).entities().isEmpty());
  }

  @Test
  void removingSourcePreservesRetainedIdentities() {
    var store = new FileStore(dir);
    var models = new OfflineModels();
    var ingestion =
        new Ingestion(
            store,
            input ->
                new Parsed(
                    new String(input.bytes(), java.nio.charset.StandardCharsets.UTF_8), null),
            models,
            models,
            new InProcessCommunities(),
            Settings.defaults());
    var corpus = ingestion.create("Two documents");
    ingestion.process(
        corpus.id(),
        List.of(
            new Input(
                "first.txt", "text/plain", "Alice Smith met Bob Jones and Carol Brown.".getBytes()),
            new Input(
                "second.txt",
                "text/plain",
                "David Green met Emily White and Frank Black.".getBytes())),
        p -> {});
    var before = store.read(corpus.id());
    String first = before.documents().get(0).id(), retained = before.documents().get(1).id();
    String passage =
        before.passages().stream()
            .filter(p -> p.documentId().equals(retained))
            .findFirst()
            .orElseThrow()
            .id();
    ingestion.removeSource(corpus.id(), first, p -> {});
    var after = store.read(corpus.id());
    assertEquals(retained, after.documents().getFirst().id());
    assertEquals(passage, after.passages().getFirst().id());
    assertTrue(after.entities().stream().noneMatch(e -> e.name().equals("Alice Smith")));
    ingestion.replaceSource(
        corpus.id(),
        retained,
        new Input(
            "replacement.txt",
            "text/plain",
            "Grace King met Henry Lake and Isabel Hill.".getBytes()),
        p -> {});
    assertEquals(retained, store.read(corpus.id()).documents().getFirst().id());
    assertTrue(
        store.read(corpus.id()).entities().stream().anyMatch(e -> e.name().equals("Grace King")));
  }

  @Test
  void removalReusesRetainedPdfExtraction() {
    var store = new FileStore(dir);
    var models = new OfflineModels();
    var pdfCalls = new java.util.concurrent.atomic.AtomicInteger();
    Ports.DocumentParser parser =
        input -> {
          if (input.name().endsWith(".pdf")) {
            assertEquals(
                1,
                pdfCalls.incrementAndGet(),
                "Retained PDF must not be parsed from reconstructed text");
            return new Parsed(
                "[Page 1] David Green met Emily White and Frank Black.",
                "PDF page markers in text");
          }
          return new Parsed(new String(input.bytes()), "UTF-8 text");
        };
    var ingestion =
        new Ingestion(
            store, parser, models, models, new InProcessCommunities(), Settings.defaults());
    var corpus = ingestion.create("Retained PDF");
    ingestion.process(
        corpus.id(),
        List.of(
            new Input("remove.txt", "text/plain", "Alice met Bob.".getBytes()),
            new Input("keep.pdf", "application/pdf", new byte[] {1})),
        p -> {});
    ingestion.removeSource(
        corpus.id(), store.read(corpus.id()).documents().getFirst().id(), p -> {});
    var retained = store.read(corpus.id());
    assertEquals(Status.READY, retained.corpus().status());
    assertEquals("application/pdf", retained.documents().getFirst().mediaType());
    assertEquals("PDF page markers in text", retained.passages().getFirst().location());
    assertEquals(1, pdfCalls.get());
  }
}
