package io.graphrag.contracts;
import org.junit.jupiter.api.Test;
import io.graphrag.core.*;
import java.time.Instant;
import java.util.*;
import static io.graphrag.core.Domain.*;
import static org.junit.jupiter.api.Assertions.*;
public abstract class StoreContract {
    protected abstract Ports.Store store();
    @Test public void scopedIdempotentPersistence(){var store=store();String one=UUID.randomUUID().toString(),two=UUID.randomUUID().toString();for(String id:List.of(one,two)){var c=new Corpus(id,"Corpus",Status.READY,Instant.now(),"DETERMINISTIC",null,null);var passage=new Passage("same-id","doc","document.txt",0,"Alice met Bob.",null,Map.of());var snap=new Snapshot(c,List.of(new Document("doc","document.txt","text/plain",Map.of())),List.of(passage),List.of(),List.of(),List.of(),List.of(new Chunk("chunk","doc","document.txt",0,passage.text(),null,List.of(1.0,0.0))),Map.of());store.save(snap);store.save(snap);}assertEquals(1,store.read(one).passages().size());assertEquals(one,store.read(one).corpus().id());assertEquals(two,store.read(two).corpus().id());assertEquals(1,store.chunks(one).size());store.activate(two);assertEquals(Optional.of(two),store.active());assertThrows(NoSuchElementException.class,()->store.read(UUID.randomUUID().toString()));}
}
