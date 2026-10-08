package io.graphrag.neo4j;
import io.graphrag.core.Domain.*;
import io.graphrag.core.Ports.CommunityDetector;
import java.util.*;
public final class LeidenCommunities implements CommunityDetector {
    private final Neo4jStore store;
    public LeidenCommunities(Neo4jStore store){this.store=store;}
    public List<Set<String>> detect(String corpusId,List<Entity> entities,List<Relationship> relationships){if(entities.isEmpty()||relationships.isEmpty())return List.of();String graph="corpus-"+corpusId+"-"+UUID.randomUUID();try(var s=store.session()){
        s.run("MATCH (a:Entity)-[r:RELATED]->(b:Entity) WHERE a.corpusId=$cid AND b.corpusId=$cid AND r.corpusId=$cid WITH gds.graph.project($name,a,b,{relationshipProperties:r{.weight}},{undirectedRelationshipTypes:['*']}) AS graph RETURN graph.graphName",Map.of("name",graph,"cid",corpusId)).consume();
        try{Map<Long,Set<String>> groups=new TreeMap<>();var result=s.run("CALL gds.leiden.stream($name, {relationshipWeightProperty:'weight', randomSeed:19, concurrency:1}) YIELD nodeId, communityId RETURN communityId,gds.util.asNode(nodeId).id AS id",Map.of("name",graph));result.forEachRemaining(r->groups.computeIfAbsent(r.get("communityId").asLong(),k->new TreeSet<>()).add(r.get("id").asString()));return groups.values().stream().filter(g->g.size()>=3).map(Set::copyOf).toList();}finally{s.run("CALL gds.graph.drop($name) YIELD graphName RETURN graphName",Map.of("name",graph)).consume();}}
    }
}
