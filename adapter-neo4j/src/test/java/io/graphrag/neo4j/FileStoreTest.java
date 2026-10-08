package io.graphrag.neo4j;
import io.graphrag.contracts.StoreContract;
import io.graphrag.core.*;
import io.graphrag.core.Domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class FileStoreTest extends StoreContract {
    @TempDir Path dir;
    protected Ports.Store store(){return new FileStore(dir);}
    @Test void allModesTracesGroundingAndRestart(){var store=new FileStore(dir);var offline=new OfflineModels();var parser=(Ports.DocumentParser)(i->new Parsed(new String(i.bytes(),java.nio.charset.StandardCharsets.UTF_8),null));var cfg=Settings.defaults();var ingestion=new Ingestion(store,parser,offline,offline,new InProcessCommunities(),cfg);var c=ingestion.create("Demo");var events=new ArrayList<Progress>();ingestion.process(c.id(),List.of(new Input("case.txt","text/plain","Sherlock Holmes met John Watson at Baker Street. John Watson helped Sherlock Holmes investigate London Bank. London Bank reported a theft at Baker Street.".getBytes())),events::add);assertEquals(Status.READY,store.read(c.id()).corpus().status());assertTrue(events.stream().anyMatch(p->p.stage().equals("extraction")&&p.entities()>0));assertTrue(events.getLast().terminal());var reopened=new FileStore(dir);assertFalse(reopened.read(c.id()).entities().isEmpty());assertFalse(reopened.read(c.id()).communities().isEmpty());var retrieval=new Retrieval(reopened,reopened,offline,offline,cfg);var answers=new Answers(retrieval,offline);for(Mode mode:Mode.values()){var answer=answers.query(c.id(),"Sherlock Holmes",mode);assertFalse(answer.noAnswer(),mode.toString());var trace=answers.trace(answer.traceId());for(Citation citation:answer.citations())assertTrue(trace.steps().stream().anyMatch(s->s.targets().contains(citation.sourceId())));if(mode==Mode.DRIFT)assertTrue(trace.steps().stream().anyMatch(s->s.kind().equals("FOLLOW_UP_QUESTION_CREATED")&&s.parentId()!=null));if(mode==Mode.VECTOR)assertTrue(answer.rankedChunks().size()>0);}assertTrue(answers.query(c.id(),"quantum neutrino",Mode.LOCAL).noAnswer());assertEquals(4,answers.compare(c.id(),"Sherlock Holmes",List.of(Mode.values())).results().size());ingestion.removeSource(c.id(),store.read(c.id()).documents().getFirst().id(),p->{});assertTrue(store.read(c.id()).entities().isEmpty());}
}
