package io.graphrag.neo4j;

import io.graphrag.core.Domain.*;
import io.graphrag.core.Ports.Store;
import java.util.*;
import org.neo4j.driver.*;

public final class Neo4jStore implements Store, AutoCloseable {
  private final Driver driver;
  private final String database;

  public Neo4jStore(String uri, String user, String password, String database) {
    driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    this.database = database;
    driver.verifyConnectivity();
    try (var s = session()) {
      s.run("CREATE CONSTRAINT corpus_id IF NOT EXISTS FOR (c:Corpus) REQUIRE c.id IS UNIQUE")
          .consume();
      s.run(
              "CREATE CONSTRAINT item_id IF NOT EXISTS FOR (n:Item) REQUIRE (n.corpusId,n.id) IS"
                  + " UNIQUE")
          .consume();
    }
  }

  public Session session() {
    return driver.session(SessionConfig.forDatabase(database));
  }

  public List<Corpus> corpora() {
    try (var s = session()) {
      return s.executeRead(
          tx ->
              tx.run("MATCH (c:Corpus) RETURN c.metadata AS data ORDER BY c.createdAt DESC")
                  .list(r -> Json.decode(r.get("data").asString(), Corpus.class)));
    }
  }

  public Snapshot read(String id) {
    try (var s = session()) {
      return s.executeRead(
          tx -> {
            var r = tx.run("MATCH (c:Corpus {id:$id}) RETURN c.snapshot AS data", Map.of("id", id));
            if (!r.hasNext()) throw new NoSuchElementException("Corpus not found");
            return Json.decode(r.single().get("data").asString(), Snapshot.class);
          });
    }
  }

  public void save(Snapshot snap) {
    String cid = snap.corpus().id();
    try (var s = session()) {
      s.executeWrite(
          tx -> {
            tx.run(
                    "MERGE (c:Corpus {id:$id}) SET"
                        + " c.metadata=$meta,c.snapshot=$snapshot,c.status=$status,c.createdAt=$created",
                    Map.of(
                        "id",
                        cid,
                        "meta",
                        Json.encode(snap.corpus()),
                        "snapshot",
                        Json.encode(snap),
                        "status",
                        snap.corpus().status().name(),
                        "created",
                        snap.corpus().createdAt().toString()))
                .consume();
            tx.run("MATCH (n:Item {corpusId:$cid}) DETACH DELETE n", Map.of("cid", cid)).consume();
            for (Document d : snap.documents())
              node(tx, "Document", cid, d.id(), d, Map.of("name", d.name()));
            for (Passage p : snap.passages())
              node(
                  tx,
                  "Passage",
                  cid,
                  p.id(),
                  p,
                  Map.of("text", p.text(), "documentId", p.documentId()));
            for (Entity e : snap.entities())
              node(
                  tx,
                  "Entity",
                  cid,
                  e.id(),
                  e,
                  Map.of(
                      "name",
                      e.name(),
                      "description",
                      e.description(),
                      "embedding",
                      snap.entityEmbeddings().getOrDefault(e.id(), List.of())));
            for (Chunk c : snap.chunks())
              node(
                  tx,
                  "Chunk",
                  cid,
                  c.id(),
                  c,
                  Map.of(
                      "text", c.text(), "embedding", c.embedding(), "documentId", c.documentId()));
            for (Community c : snap.communities()) {
              node(
                  tx,
                  "Community",
                  cid,
                  c.id(),
                  c,
                  Map.of("title", c.title(), "summary", c.summary(), "embedding", c.embedding()));
              for (String member : c.members())
                tx.run(
                        "MATCH (c:Community {corpusId:$cid,id:$community}),(e:Entity"
                            + " {corpusId:$cid,id:$entity}) MERGE (e)-[:MEMBER_OF]->(c)",
                        Map.of("cid", cid, "community", c.id(), "entity", member))
                    .consume();
            }
            for (Entity e : snap.entities())
              for (String pid : e.passageIds()) support(tx, cid, e.id(), pid);
            for (Relationship r : snap.relationships())
              tx.run(
                      "MATCH (a:Entity {corpusId:$cid,id:$source}),(b:Entity"
                          + " {corpusId:$cid,id:$target}) MERGE (a)-[r:RELATED {id:$id}]->(b) SET"
                          + " r.corpusId=$cid,r.type=$type,r.weight=$weight,r.data=$data,r.passageIds=$passages",
                      Map.of(
                          "cid",
                          cid,
                          "source",
                          r.source(),
                          "target",
                          r.target(),
                          "id",
                          r.id(),
                          "type",
                          r.type(),
                          "weight",
                          r.weight(),
                          "data",
                          Json.encode(r),
                          "passages",
                          List.copyOf(r.passageIds())))
                  .consume();
            return null;
          });
    }
  }

  private static void node(
      TransactionContext tx,
      String label,
      String cid,
      String id,
      Object data,
      Map<String, Object> props) {
    tx.run(
            "MERGE (n:Item:" + label + " {corpusId:$cid,id:$id}) SET n.data=$data SET n += $props",
            Map.of("cid", cid, "id", id, "data", Json.encode(data), "props", props))
        .consume();
  }

  private static void support(TransactionContext tx, String cid, String entity, String passage) {
    tx.run(
            "MATCH (e:Entity {corpusId:$cid,id:$e}),(p:Passage {corpusId:$cid,id:$p}) MERGE"
                + " (e)-[:SUPPORTED_BY]->(p)",
            Map.of("cid", cid, "e", entity, "p", passage))
        .consume();
  }

  public List<Chunk> chunks(String id) {
    try (var s = session()) {
      return s.executeRead(
          tx ->
              tx.run(
                      "MATCH (n:Chunk {corpusId:$id}) RETURN n.data AS data ORDER BY n.id",
                      Map.of("id", id))
                  .list(r -> Json.decode(r.get("data").asString(), Chunk.class)));
    }
  }

  public void activate(String id) {
    read(id);
    try (var s = session()) {
      s.executeWrite(
          tx -> {
            tx.run("MERGE (s:ExplorerSettings {id:'active'}) SET s.corpusId=$id", Map.of("id", id))
                .consume();
            return null;
          });
    }
  }

  public Optional<String> active() {
    try (var s = session()) {
      return s.executeRead(
          tx -> {
            var r = tx.run("MATCH (s:ExplorerSettings {id:'active'}) RETURN s.corpusId AS id");
            return r.hasNext() ? Optional.of(r.single().get("id").asString()) : Optional.empty();
          });
    }
  }

  public void close() {
    driver.close();
  }
}
