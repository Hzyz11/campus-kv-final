package edu.baodex.campus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class KvStateTest {
 @Test void atomicBatchAndIdempotencySurviveSnapshot(){
  var s=new KvState();var c=KvState.obj().put("op","CAS").put("key","a").put("expected",0).put("value",7).put("requestId","r1").put("digest","d1");assertEquals("OK",s.apply(c).path("code").asText());
  var restored=new KvState();restored.restore(s.export());assertEquals("OK",restored.apply(c).path("code").asText());assertEquals(1,restored.versions.get("a"));
  var stale=c.deepCopy().put("requestId","r2");assertEquals("VERSION_CONFLICT",restored.apply(stale).path("code").asText());assertFalse(restored.requests.containsKey("r2"));
  assertEquals("REQUEST_ID_REUSED",restored.apply(c.deepCopy().put("digest","other")).path("code").asText());
  var updates=KvState.JSON.createArrayNode().add(KvState.obj().put("key","b").put("expected",0).put("value",9)).add(KvState.obj().put("key","a").put("expected",0).put("value",9));
  var batch=KvState.obj().put("op","BATCH").put("requestId","r3").put("digest","d3");batch.set("updates",updates);assertEquals("VERSION_CONFLICT",restored.apply(batch).path("code").asText());assertFalse(restored.values.containsKey("b"));
 }
 @Test void deletionKeepsVersionToPreventABA(){var s=new KvState();for(String op:new String[]{"PUT","DELETE"})s.apply(KvState.obj().put("op",op).put("key","a").put("value",1).put("requestId",op).put("digest",op));assertFalse(s.values.containsKey("a"));assertEquals(2,s.versions.get("a"));assertEquals("VERSION_CONFLICT",s.apply(KvState.obj().put("op","CAS").put("key","a").put("expected",0).put("value",2).put("requestId","cas").put("digest","cas")).path("code").asText());}
}
