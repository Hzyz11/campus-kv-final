package edu.baodex.campus;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Deterministic replicated state. All reads, including GET/SCAN, use committed commands. */
public class KvState {
  public static final ObjectMapper JSON = new ObjectMapper();
  public final TreeMap<String, JsonNode> values = new TreeMap<>();
  public final TreeMap<String, Long> versions = new TreeMap<>();
  public final TreeMap<String, JsonNode> requests = new TreeMap<>();

  public static ObjectNode obj() {
    return JSON.createObjectNode();
  }

  public static ObjectNode result(String code) {
    return obj().put("code", code);
  }

  public synchronized JsonNode apply(JsonNode c) {
    String op = c.path("op").asText(), key = c.path("key").asText();
    if (op.equals("GET"))
      return result("OK")
          .put("version", versions.getOrDefault(key, 0L))
          .set("value", values.getOrDefault(key, NullNode.instance));
    if (op.equals("SCAN")) {
      ObjectNode r = result("OK"), data = obj();
      values.forEach(
          (k, v) -> {
            if (k.startsWith(key)) data.set(k, v);
          });
      return r.set("value", data);
    }
    if (op.equals("RESULT")) return requests.getOrDefault(key, result("NOT_FOUND")).deepCopy();
    String rid = c.path("requestId").asText(), digest = c.path("digest").asText();
    if (rid.isBlank() || digest.isBlank()) return result("INVALID_REQUEST");
    if (requests.containsKey(rid)) {
      JsonNode saved = requests.get(rid);
      return saved.path("digest").asText().equals(digest)
          ? saved.path("result").deepCopy()
          : result("REQUEST_ID_REUSED");
    }
    if (!Set.of("PUT", "DELETE", "CAS", "BATCH").contains(op)) return result("INVALID_OPERATION");
    if (op.equals("CAS") && !c.has("expected")) return result("INVALID_REQUEST");
    ArrayNode updates = JSON.createArrayNode();
    if (op.equals("BATCH")) {
      if (!c.path("updates").isArray()) return result("INVALID_REQUEST");
      updates = (ArrayNode) c.path("updates");
    } else {
      ObjectNode u = obj().put("key", key).put("delete", op.equals("DELETE"));
      if (c.has("expected")) u.set("expected", c.get("expected"));
      u.set("value", c.path("value"));
      updates.add(u);
    }
    Set<String> seen = new HashSet<>();
    for (JsonNode u : updates) {
      String k = u.path("key").asText();
      if (k.isBlank() || !seen.add(k)) return result("INVALID_REQUEST");
      if (u.has("expected") && u.path("expected").asLong() != versions.getOrDefault(k, 0L))
        return result("VERSION_CONFLICT");
    }
    for (JsonNode u : updates) {
      String k = u.path("key").asText();
      versions.put(k, versions.getOrDefault(k, 0L) + 1);
      if (u.path("delete").asBoolean()) values.remove(k);
      else values.put(k, u.path("value").deepCopy());
    }
    JsonNode answer = c.has("answer") ? c.get("answer").deepCopy() : result("OK");
    requests.put(rid, obj().put("digest", digest).set("result", answer));
    return answer;
  }

  public synchronized ObjectNode export() {
    ObjectNode r = obj();
    r.set("values", JSON.valueToTree(values));
    r.set("versions", JSON.valueToTree(versions));
    r.set("requests", JSON.valueToTree(requests));
    return r;
  }

  public synchronized void restore(JsonNode n) {
    values.clear();
    versions.clear();
    requests.clear();
    n.path("values").fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue()));
    n.path("versions")
        .fields()
        .forEachRemaining(e -> versions.put(e.getKey(), e.getValue().asLong()));
    n.path("requests").fields().forEachRemaining(e -> requests.put(e.getKey(), e.getValue()));
  }
}
