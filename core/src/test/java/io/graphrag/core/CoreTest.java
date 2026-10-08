package io.graphrag.core;
import org.junit.jupiter.api.Test;
import java.util.*;
import static io.graphrag.core.Domain.*;
import static org.junit.jupiter.api.Assertions.*;
class CoreTest {
    @Test void normalizationAndSplitting(){assertEquals("strasse",Text.normalize("  ‘Straße’  "));assertEquals(Text.normalize("Ａlice"),Text.normalize("Alice"));assertEquals(List.of("abcdef","efghij"),Text.split("abcdefghij",6,2));assertThrows(IllegalArgumentException.class,()->Text.split("x",5,5));}
    @Test void mergePreservesProvenanceAndEndpoints(){Map<String,Entity> es=new TreeMap<>();Map<String,Relationship> rs=new TreeMap<>();for(int i=0;i<2;i++){var p=new Passage("p"+i,"d","d.txt",i,"Alice met Bob",null,Map.of());Ingestion.merge("c",p,List.of(new EntityDraft(i==0?"Alice":" ALICE! ","Person","Alice met Bob"),new EntityDraft("Bob","Person","Bob")),List.of(new RelationDraft("alice","bob","MET","Meeting",1)),es,rs);}assertEquals(2,es.size());assertEquals(1,rs.size());assertEquals(Set.of("p0","p1"),rs.values().iterator().next().passageIds());}
    @Test void offlineAnswerDoesNotInventEvidence(){var m=new OfflineModels();assertEquals("NO_ANSWER",m.answer("quantum mechanics",List.of(new Evidence("p","d","Alice met Bob.","passage"))));assertTrue(m.answer("Alice",List.of(new Evidence("p","d","Alice met Bob.","passage"))).contains("[1]"));}
}
