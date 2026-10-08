package io.graphrag.core;

import java.time.Instant;
import java.util.*;

public final class Domain {
    private Domain() {}
    public enum Status { BUILDING, READY, FAILED }
    public enum Mode { LOCAL, GLOBAL, DRIFT, VECTOR }
    public enum Matching { KEYWORD, SEMANTIC, IDENTIFIER, HYBRID }
    public record Corpus(String id, String name, Status status, Instant createdAt, String mode, String failedStage, String reason) {
        public Corpus state(Status status, String stage, String reason) { return new Corpus(id,name,status,createdAt,mode,stage,reason); }
    }
    public record Document(String id, String name, String mediaType, Map<String,String> attributes) {}
    public record Passage(String id, String documentId, String documentName, int ordinal, String text, String location, Map<String,String> attributes) {}
    public record Entity(String id, String name, String type, String description, Set<String> passageIds, Map<String,String> attributes) {}
    public record Relationship(String id, String source, String target, String type, String description, double weight, Set<String> passageIds) {}
    public record Community(String id, String title, String summary, Set<String> members, List<Double> embedding) {}
    public record Chunk(String id, String documentId, String documentName, int ordinal, String text, String location, List<Double> embedding) {}
    public record Citation(int number, String sourceId, String documentName, String excerpt, String sourceKind) {}
    public record Step(String id, String kind, String label, List<String> targets, String parentId, Map<String,Object> metadata) {}
    public record Trace(String id, String corpusId, Mode mode, String question, List<Step> steps) {}
    public record Timing(long retrievalMs, long embeddingMs, long generationMs) { public long totalMs() { return retrievalMs+embeddingMs+generationMs; } }
    public record RankedChunk(Chunk chunk, double score, boolean inContext) {}
    public record Evidence(String id, String documentName, String text, String kind) {}
    public record Context(List<Evidence> evidence, Trace trace, List<RankedChunk> rankedChunks, long retrievalMs, long embeddingMs, long generationMs) {}
    public record Answer(Mode mode, String question, String text, List<Citation> citations, String traceId, boolean noAnswer, Timing timing, int contextSize, int documentCoverage, List<RankedChunk> rankedChunks) {}
    public record Comparison(Map<Mode,Answer> results, Map<Mode,String> errors, List<EvidenceState> matrix, double evidenceOverlap, String explanation) {}
    public record EvidenceState(String id, String documentName, String excerpt, Map<Mode,String> states) {}
    public record Progress(long sequence, String stage, String message, int passages, int entities, int relationships, int communities, boolean terminal) {}
    public record Snapshot(Corpus corpus, List<Document> documents, List<Passage> passages, List<Entity> entities, List<Relationship> relationships, List<Community> communities, List<Chunk> chunks, Map<String,List<Double>> entityEmbeddings) {
        public static Snapshot empty(Corpus corpus) { return new Snapshot(corpus,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Map.of()); }
        public Snapshot status(Status status,String stage,String reason) { return new Snapshot(corpus.state(status,stage,reason),documents,passages,entities,relationships,communities,chunks,entityEmbeddings); }
    }
    public record EntityDraft(String name, String type, String description) {}
    public record RelationDraft(String source, String target, String type, String description, double weight) {}
    public record Summary(String title, String text) {}
    public record Parsed(String text, String location) {}
    public record Input(String name, String mediaType, byte[] bytes) {}
    public record Settings(int passageSize, int overlap, int chunkSize, int seeds, int relationships, int passages, int communities, int passagesPerCommunity, int followups, int vectorContext, int vectorInspect) {
        public Settings { if(passageSize<100 || overlap<0 || overlap>=passageSize || chunkSize<50 || seeds<1 || relationships<1 || passages<1 || communities<1 || passagesPerCommunity<1 || followups<1 || followups>10 || vectorContext<1 || vectorInspect<vectorContext) throw new IllegalArgumentException("Invalid ingestion or retrieval limits"); }
        public static Settings defaults() { return new Settings(6000,600,500,3,10,5,3,2,3,5,12); }
    }
}
