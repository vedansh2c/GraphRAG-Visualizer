package io.graphrag.neo4j;
import io.graphrag.contracts.StoreContract;
import io.graphrag.core.*;
import io.graphrag.core.Domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="NEO4J_TEST_URI",matches=".+")
class Neo4jIntegrationTest extends StoreContract {
    private Neo4jStore db;
    protected Ports.Store store(){if(db==null)db=new Neo4jStore(System.getenv("NEO4J_TEST_URI"),"neo4j",System.getenv().getOrDefault("NEO4J_TEST_PASSWORD","explorer-test-password"),"neo4j");return db;}
    @AfterEach void close(){if(db!=null)db.close();}
    @Test void leidenAndRestart(){var s=store();var models=new OfflineModels();var ingestion=new Ingestion(s,i->new Parsed(new String(i.bytes()),null),models,models,new LeidenCommunities(db),Settings.defaults());var c=ingestion.create("GDS integration");ingestion.process(c.id(),List.of(new Input("case.txt","text/plain","Alice Smith met Bob Jones and Carol Brown. Bob Jones helped Carol Brown and Alice Smith.".getBytes())),p->{});assertEquals(Status.READY,s.read(c.id()).corpus().status(),s.read(c.id()).corpus().reason());assertFalse(s.read(c.id()).communities().isEmpty());db.close();db=null;assertEquals(Status.READY,store().read(c.id()).corpus().status());}
}
