package io.graphrag.web;

import io.graphrag.core.*;
import io.graphrag.core.Domain.Settings;
import io.graphrag.core.Ports.*;
import io.graphrag.model.LiveModels;
import io.graphrag.neo4j.*;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@org.springframework.context.annotation.Configuration
public class Configuration {
  @Bean
  Store store(Environment e) {
    return e.getProperty("explorer.store", "neo4j").equals("file")
        ? new FileStore(Path.of(e.getProperty("explorer.data-dir", ".data")))
        : new Neo4jStore(
            e.getRequiredProperty("explorer.neo4j-uri"),
            e.getRequiredProperty("explorer.neo4j-user"),
            e.getRequiredProperty("explorer.neo4j-password"),
            e.getRequiredProperty("explorer.neo4j-database"));
  }

  @Bean
  CommunityDetector communities(Store store, Environment e) {
    return e.getProperty("explorer.community-detector", "gds").equals("gds")
            && store instanceof Neo4jStore neo
        ? new LeidenCommunities(neo)
        : new InProcessCommunities();
  }

  @Bean
  OfflineModels offlineModels() {
    return new OfflineModels();
  }

  @Bean
  ModelSet models(Environment e, OfflineModels offline) {
    String key = e.getProperty("explorer.api-key", "");
    if (key.isBlank()) return new ModelSet(offline, offline);
    var live =
        new LiveModels(
            key,
            e.getRequiredProperty("explorer.base-url"),
            e.getRequiredProperty("explorer.chat-model"),
            e.getRequiredProperty("explorer.embedding-model"),
            e.getProperty("explorer.model-timeout", Integer.class, 90));
    return new ModelSet(live, live);
  }

  public record ModelSet(LanguageModel language, Embeddings embeddings) {}

  @Bean
  Settings settings(Environment e) {
    return new Settings(
        e.getProperty("explorer.passage-size", Integer.class, 6000),
        e.getProperty("explorer.overlap", Integer.class, 600),
        e.getProperty("explorer.chunk-size", Integer.class, 500),
        e.getProperty("explorer.seeds", Integer.class, 3),
        e.getProperty("explorer.relationships", Integer.class, 10),
        e.getProperty("explorer.passages", Integer.class, 5),
        e.getProperty("explorer.communities", Integer.class, 3),
        e.getProperty("explorer.passages-per-community", Integer.class, 2),
        e.getProperty("explorer.followups", Integer.class, 3),
        e.getProperty("explorer.vector-context", Integer.class, 5),
        e.getProperty("explorer.vector-inspect", Integer.class, 12));
  }
}
