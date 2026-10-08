package io.graphrag.neo4j;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
public final class Json {
    public static final ObjectMapper MAPPER=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private Json(){}
    public static String encode(Object value){try{return MAPPER.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode persisted data",e);}}
    public static <T>T decode(String value,Class<T> type){try{return MAPPER.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Cannot read persisted data",e);}}
}
