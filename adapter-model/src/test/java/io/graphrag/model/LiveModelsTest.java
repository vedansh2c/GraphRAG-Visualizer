package io.graphrag.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.graphrag.core.Domain.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LiveModelsTest {
  @Test
  void realLangchainAdapterWorksAgainstMockProvider() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger calls = new AtomicInteger();
    ObjectMapper json = new ObjectMapper();
    server.createContext(
        "/v1",
        exchange -> {
          calls.incrementAndGet();
          String request =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String response;
          if (exchange.getRequestURI().getPath().endsWith("embeddings"))
            response =
                "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1.0,0.0]}],\"model\":\"mock\",\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";
          else {
            String output =
                request.contains("Extract explicitly")
                    ? "{\"entities\":[{\"name\":\"Alice\",\"type\":\"Person\",\"description\":\"Alice"
                          + " met Bob.\"}]}"
                    : request.contains("Extract supported")
                        ? "{\"relationships\":[{\"source\":\"Alice\",\"target\":\"Bob\",\"type\":\"MET\",\"description\":\"Alice"
                              + " met Bob.\",\"weight\":1}]}"
                        : request.contains("Summarize only")
                            ? "{\"title\":\"Meeting\",\"text\":\"Alice met Bob.\"}"
                            : request.contains("short evidence-seeking")
                                ? "{\"questions\":[\"Who met Alice?\"]}"
                                : "Alice met Bob. [1]";
            response =
                json.writeValueAsString(
                    Map.of(
                        "id",
                        "mock",
                        "object",
                        "chat.completion",
                        "created",
                        1,
                        "model",
                        "mock",
                        "choices",
                        List.of(
                            Map.of(
                                "index",
                                0,
                                "message",
                                Map.of("role", "assistant", "content", output),
                                "finish_reason",
                                "stop")),
                        "usage",
                        Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
          }
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var live =
          new LiveModels(
              "mock-key",
              "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
              "mock",
              "mock",
              5);
      var es = live.entities("Alice met Bob.");
      assertEquals("Alice", es.getFirst().name());
      assertEquals("MET", live.relationships("Alice met Bob.", es).getFirst().type());
      assertEquals("Meeting", live.summarize(List.of(), List.of()).title());
      assertEquals(List.of("Who met Alice?"), live.followups("Alice", List.of(), 2));
      assertEquals(
          "Alice met Bob. [1]",
          live.answer(
              "Who met Alice?", List.of(new Evidence("p", "d", "Alice met Bob.", "passage"))));
      assertEquals(List.of(1.0, 0.0), live.embed("Alice"));
      assertEquals(6, calls.get());
    } finally {
      server.stop(0);
    }
  }
}
