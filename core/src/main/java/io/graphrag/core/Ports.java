package io.graphrag.core;
import java.util.*;
import static io.graphrag.core.Domain.*;
public final class Ports {
    private Ports() {}
    public interface DocumentParser { Parsed parse(Input input); }
    public interface LanguageModel {
        List<EntityDraft> entities(String text);
        List<RelationDraft> relationships(String text,List<EntityDraft> entities);
        Summary summarize(List<Entity> entities,List<Relationship> relationships);
        List<String> followups(String question,List<Community> communities,int limit);
        String answer(String question,List<Evidence> context);
        boolean offline();
    }
    public interface Embeddings { List<Double> embed(String text); }
    public interface GraphRead { List<Corpus> corpora(); Snapshot read(String corpusId); Optional<String> active(); }
    public interface GraphWrite { void save(Snapshot snapshot); void activate(String corpusId); }
    public interface VectorStorage { List<Chunk> chunks(String corpusId); }
    public interface Store extends GraphRead,GraphWrite,VectorStorage {}
    public interface CommunityDetector { List<Set<String>> detect(String corpusId,List<Entity> entities,List<Relationship> relationships); }
    public interface ProgressSink { void emit(Progress progress); }
}
