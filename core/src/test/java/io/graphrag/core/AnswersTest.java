package io.graphrag.core;

import static io.graphrag.core.Domain.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnswersTest {
  private static final class FakeModel implements Ports.LanguageModel {
    final String output;
    final OfflineModels delegate = new OfflineModels();

    FakeModel(String output) {
      this.output = output;
    }

    public boolean offline() {
      return true;
    }

    public List<EntityDraft> entities(String t) {
      return delegate.entities(t);
    }

    public List<RelationDraft> relationships(String t, List<EntityDraft> e) {
      return delegate.relationships(t, e);
    }

    public Summary summarize(List<Entity> e, List<Relationship> r) {
      return delegate.summarize(e, r);
    }

    public List<String> followups(String q, List<Community> c, int l) {
      return delegate.followups(q, c, l);
    }

    public String answer(String q, List<Evidence> c) {
      return output;
    }
  }

  private Answers engine(String text) {
    var model = new FakeModel(text);
    var passages =
        List.of(
            new Passage("a", "d", "a.txt", 0, "Alice met Bob.", null, Map.of()),
            new Passage("b", "e", "b.txt", 0, "Alice met Carol.", null, Map.of()));
    var snapshot =
        new Snapshot(
            new Corpus("c", "Test", Status.READY, Instant.now(), "DETERMINISTIC", null, null),
            List.of(),
            passages,
            List.of(new Entity("entity", "Alice", "Person", "Alice", Set.of("a", "b"), Map.of())),
            List.of(),
            List.of(),
            List.of(),
            Map.of());
    var graph =
        new Ports.GraphRead() {
          public Snapshot read(String id) {
            return snapshot;
          }

          public List<Corpus> corpora() {
            return List.of(snapshot.corpus());
          }

          public Optional<String> active() {
            return Optional.of("c");
          }
        };
    return new Answers(
        new Retrieval(graph, id -> List.of(), new OfflineModels(), model, Settings.defaults()),
        model);
  }

  @Test
  void rejectsCitationsOutsideContext() {
    assertThrows(
        IllegalArgumentException.class,
        () -> engine("Invented [99]").query("c", "Alice", Mode.LOCAL));
  }

  @Test
  void renumbersAndDeduplicatesReferences() {
    var a = engine("Second [2], first [1], second again [2].").query("c", "Alice", Mode.LOCAL);
    assertEquals("Second [1], first [2], second again [1].", a.text());
    assertEquals(2, a.citations().size());
    assertEquals("b", a.citations().getFirst().sourceId());
  }

  @Test
  void refusesUncitedModelResponse() {
    assertTrue(engine("Alice did something.").query("c", "Alice", Mode.LOCAL).noAnswer());
  }

  @Test
  void missingCommunitiesNeverFabricateGlobalOrDrift() {
    for (Mode m : List.of(Mode.GLOBAL, Mode.DRIFT)) {
      var a = engine("Invented [1]").query("c", "Alice", m);
      assertTrue(a.noAnswer());
    }
  }
}
