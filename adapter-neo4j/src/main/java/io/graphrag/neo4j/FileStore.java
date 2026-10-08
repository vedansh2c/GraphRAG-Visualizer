package io.graphrag.neo4j;
import io.graphrag.core.Domain.*;
import io.graphrag.core.Ports.Store;
import java.nio.file.*;
import java.util.*;
/** Explicit single-process file profile for offline use without Docker. Production profile uses Neo4j. */
public final class FileStore implements Store {
    private final Path dir;
    public FileStore(Path dir){this.dir=dir;try{Files.createDirectories(dir);}catch(Exception e){throw new IllegalStateException(e);}}
    private Path path(String id){if(!id.matches("[a-zA-Z0-9-]{1,100}"))throw new IllegalArgumentException("Invalid corpus ID");return dir.resolve(id+".json");}
    public synchronized void save(Snapshot s){try{Path p=path(s.corpus().id()),tmp=Files.createTempFile(dir,"write-",".tmp");Files.writeString(tmp,Json.encode(s));Files.move(tmp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(Exception e){throw new IllegalStateException("Cannot persist corpus",e);}}
    public synchronized Snapshot read(String id){Path p=path(id);if(!Files.exists(p))throw new NoSuchElementException("Corpus not found");try{return Json.decode(Files.readString(p),Snapshot.class);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    public synchronized List<Corpus> corpora(){try(var paths=Files.list(dir)){return paths.filter(p->p.toString().endsWith(".json")).map(p->{try{return Json.decode(Files.readString(p),Snapshot.class).corpus();}catch(Exception e){throw new IllegalStateException(e);}}).sorted(Comparator.comparing(Corpus::createdAt).reversed()).toList();}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    public List<Chunk> chunks(String corpusId){return read(corpusId).chunks();}
    public synchronized void activate(String id){read(id);try{Files.writeString(dir.resolve("active"),id);}catch(Exception e){throw new IllegalStateException(e);}}
    public synchronized Optional<String> active(){try{Path p=dir.resolve("active");return Files.exists(p)?Optional.of(Files.readString(p)):Optional.empty();}catch(Exception e){throw new IllegalStateException(e);}}
}
