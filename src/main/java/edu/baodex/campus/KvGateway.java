package edu.baodex.campus;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.protocol.Message;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("distributed")
public class KvGateway implements AutoCloseable {
  final RaftClient client = KvCluster.client();

  public synchronized JsonNode send(JsonNode c) {
    try {
      var r = client.io().send(Message.valueOf(c.toString()));
      if (!r.isSuccess()) throw new IllegalStateException(r.toString());
      return KvState.JSON.readTree(r.getMessage().getContent().toStringUtf8());
    } catch (Exception e) {
      throw new Problem(503, "集群暂不可确认结果，请保留请求 ID 查询或重试");
    }
  }

  public JsonNode get(String key) {
    return send(KvState.obj().put("op", "GET").put("key", key));
  }

  public JsonNode scan(String prefix) {
    return send(KvState.obj().put("op", "SCAN").put("key", prefix)).path("value");
  }

  public static String digest(JsonNode c) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(c.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() throws Exception {
    client.close();
  }
}
