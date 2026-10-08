package io.graphrag.web;

import io.graphrag.core.Domain.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class Api {
  private final ExplorerService service;
  private final ProgressEvents progress;

  public Api(ExplorerService service, ProgressEvents progress) {
    this.service = service;
    this.progress = progress;
  }

  public record Query(String question, Mode mode) {}

  @GetMapping("/corpora")
  public Map<String, Object> corpora() {
    return Map.of(
        "corpora", service.store.corpora(), "activeId", service.store.active().orElse(""));
  }

  @PostMapping(value = "/corpora", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Corpus upload(
      @RequestParam(defaultValue = "Uploaded documents") String name,
      @RequestParam(value = "files", required = false) List<MultipartFile> files,
      @RequestParam(defaultValue = "false") boolean offline)
      throws java.io.IOException {
    List<Input> inputs = new ArrayList<>();
    if (files != null)
      for (var f : files) {
        String filename = f.getOriginalFilename();
        if (filename == null || filename.isBlank())
          throw new IllegalArgumentException("Missing file name");
        filename = filename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        inputs.add(
            new Input(
                filename,
                f.getContentType() == null ? "application/octet-stream" : f.getContentType(),
                f.getBytes()));
      }
    return service.create(name, inputs, offline);
  }

  @PostMapping("/corpora/demo")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Corpus demo() {
    return service.demo(false);
  }

  @PostMapping("/corpora/demo-offline")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Corpus demoOffline() {
    return service.demo(true);
  }

  @PostMapping("/corpora/{id}/activate")
  public Corpus activate(@PathVariable String id) {
    service.store.activate(id);
    return service.store.read(id).corpus();
  }

  @GetMapping(value = "/corpora/{id}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter progress(
      @PathVariable String id,
      @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long last) {
    return progress.subscribe(service.store.read(id), last);
  }

  @GetMapping("/corpora/{id}/graph")
  public Map<String, Object> graph(@PathVariable String id) {
    var s = service.store.read(id);
    return Map.of(
        "corpus",
        s.corpus(),
        "documents",
        s.documents(),
        "passages",
        s.passages().stream()
            .map(
                p -> Map.of("id", p.id(), "documentName", p.documentName(), "ordinal", p.ordinal()))
            .toList(),
        "entities",
        s.entities(),
        "relationships",
        s.relationships(),
        "communities",
        s.communities());
  }

  @GetMapping("/corpora/{id}/text-units/{passageId}")
  public Passage passage(@PathVariable String id, @PathVariable String passageId) {
    return service.store.read(id).passages().stream()
        .filter(p -> p.id().equals(passageId))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("Passage not found in this corpus"));
  }

  @GetMapping("/corpora/{id}/chunks/{chunkId}")
  public Chunk chunk(@PathVariable String id, @PathVariable String chunkId) {
    return service.store.chunks(id).stream()
        .filter(c -> c.id().equals(chunkId))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("Chunk not found in this corpus"));
  }

  @PostMapping("/corpora/{id}/query")
  public Answer query(@PathVariable String id, @RequestBody Query q) {
    return service.query(id, q.question(), q.mode() == null ? Mode.LOCAL : q.mode());
  }

  @PostMapping("/corpora/{id}/compare")
  public Comparison compare(@PathVariable String id, @RequestBody Query q) {
    Mode mode = q.mode() == null ? Mode.LOCAL : q.mode();
    if (mode == Mode.VECTOR)
      throw new IllegalArgumentException("Select a graph method to compare with Vector");
    return service.compare(id, q.question(), List.of(mode, Mode.VECTOR));
  }

  @PostMapping("/corpora/{id}/compare-all")
  public Comparison compareAll(@PathVariable String id, @RequestBody Query q) {
    return service.compare(id, q.question(), List.of(Mode.values()));
  }

  @GetMapping("/traces/{traceId}")
  public Trace trace(@PathVariable String traceId) {
    return service.trace(traceId);
  }
}
