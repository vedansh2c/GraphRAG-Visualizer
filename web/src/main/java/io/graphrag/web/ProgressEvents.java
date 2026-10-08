package io.graphrag.web;

import io.graphrag.core.Domain.Progress;
import io.graphrag.core.Domain.Snapshot;
import io.graphrag.core.Domain.Status;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public final class ProgressEvents {
  private final Map<String, Channel> channels = new ConcurrentHashMap<>();

  private static final class Channel {
    final List<Progress> events = new ArrayList<>();
    final Set<SseEmitter> clients = new HashSet<>();
  }

  public void publish(String corpus, Progress event) {
    Channel c = channels.computeIfAbsent(corpus, k -> new Channel());
    synchronized (c) {
      c.events.add(event);
      if (c.events.size() > 1000) c.events.removeFirst();
      for (var client : List.copyOf(c.clients)) send(c, client, event);
    }
  }

  private void send(Channel c, SseEmitter client, Progress p) {
    try {
      client.send(SseEmitter.event().id(Long.toString(p.sequence())).name("progress").data(p));
      if (p.terminal()) {
        c.clients.remove(client);
        client.complete();
      }
    } catch (Exception e) {
      c.clients.remove(client);
      client.complete();
    }
  }

  public SseEmitter subscribe(Snapshot snapshot, long last) {
    String id = snapshot.corpus().id();
    Channel c = channels.computeIfAbsent(id, k -> new Channel());
    SseEmitter emitter = new SseEmitter(0L);
    Runnable cleanup =
        () -> {
          synchronized (c) {
            c.clients.remove(emitter);
          }
        };
    emitter.onCompletion(cleanup);
    emitter.onTimeout(cleanup);
    emitter.onError(e -> cleanup.run());
    synchronized (c) {
      c.clients.add(emitter);
      if (c.events.isEmpty() && snapshot.corpus().status() != Status.BUILDING) {
        boolean ready = snapshot.corpus().status() == Status.READY;
        send(
            c,
            emitter,
            new Progress(
                1,
                ready ? "complete" : "error",
                ready ? "Corpus is ready" : snapshot.corpus().reason(),
                snapshot.passages().size(),
                snapshot.entities().size(),
                snapshot.relationships().size(),
                snapshot.communities().size(),
                true));
      } else {
        for (Progress p : c.events) if (p.sequence() > last) send(c, emitter, p);
        if (!c.events.isEmpty() && c.events.getLast().terminal()) emitter.complete();
      }
    }
    return emitter;
  }

  @Scheduled(fixedDelay = 15000)
  public void heartbeat() {
    for (Channel c : channels.values())
      synchronized (c) {
        for (SseEmitter emitter : List.copyOf(c.clients))
          try {
            emitter.send(SseEmitter.event().comment("heartbeat"));
          } catch (Exception e) {
            c.clients.remove(emitter);
            emitter.complete();
          }
      }
  }
}
