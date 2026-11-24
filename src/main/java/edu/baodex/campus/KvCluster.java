package edu.baodex.campus;

import com.sun.net.httpserver.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ratis.client.*;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.protocol.*;
import org.apache.ratis.retry.RetryPolicies;
import org.apache.ratis.server.*;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.util.TimeDuration;

public class KvCluster {
  public static final RaftGroupId GROUP_ID =
      RaftGroupId.valueOf(UUID.fromString("f35e8650-49bb-47b3-8150-d8f24c57ac11"));

  public static RaftGroup group() {
    List<RaftPeer> p = new ArrayList<>();
    for (int i = 0; i < 3; i++)
      p.add(RaftPeer.newBuilder().setId("n" + i).setAddress("127.0.0.1:" + (19000 + i)).build());
    return RaftGroup.valueOf(GROUP_ID, p);
  }

  public static RaftClient client() {
    RaftProperties p = new RaftProperties();
    p.set("raft.client.rpc.request.timeout", "3s");
    return RaftClient.newBuilder()
        .setRaftGroup(group())
        .setProperties(p)
        .setRetryPolicy(
            RetryPolicies.retryUpToMaximumCountWithFixedSleep(
                5, TimeDuration.valueOf(200, TimeUnit.MILLISECONDS)))
        .build();
  }

  public static void main(String[] args) throws Exception {
    int i = Integer.parseInt(args[0]);
    if (i < 0 || i > 2) throw new IllegalArgumentException("node 0..2");
    RaftProperties p = new RaftProperties();
    RaftServerConfigKeys.setStorageDir(p, List.of(new File("data/node-" + i)));
    GrpcConfigKeys.Server.setHost(p, "127.0.0.1");
    GrpcConfigKeys.Server.setPort(p, 19300 + i);
    p.set("raft.server.log.force.sync.num", "1");
    p.set("raft.server.rpc.timeout.min", "800ms");
    p.set("raft.server.rpc.timeout.max", "1600ms");
    LinkProxy proxy = new LinkProxy(i);
    KvMachine machine = new KvMachine();
    var server =
        RaftServer.newBuilder()
            .setServerId(RaftPeerId.valueOf("n" + i))
            .setGroup(group())
            .setStateMachine(machine)
            .setProperties(p)
            .setOption(RaftStorage.StartupOption.RECOVER)
            .build();
    server.start();
    HttpServer http = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 19100 + i), 0);
    http.setExecutor(Executors.newCachedThreadPool());
    http.createContext(
        "/probe",
        x -> {
          try {
            var command = KvState.JSON.readTree(x.getRequestBody());
            var request =
                RaftClientRequest.newBuilder()
                    .setClientId(ClientId.randomId())
                    .setServerId(RaftPeerId.valueOf("n" + i))
                    .setGroupId(GROUP_ID)
                    .setCallId(0)
                    .setType(RaftClientRequest.writeRequestType())
                    .setMessage(Message.valueOf(command.toString()))
                    .build();
            var reply = server.submitClientRequestAsync(request).get(4, TimeUnit.SECONDS);
            byte[] bytes =
                (reply.isSuccess()
                        ? reply.getMessage().getContent().toStringUtf8()
                        : "{\"code\":\"NOT_LEADER\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            x.sendResponseHeaders(reply.isSuccess() ? 200 : 409, bytes.length);
            x.getResponseBody().write(bytes);
          } catch (Exception e) {
            x.sendResponseHeaders(503, -1);
          } finally {
            x.close();
          }
        });
    http.createContext(
        "/status",
        x -> {
          try {
            var d = server.getDivision(GROUP_ID);
            var r = machine.state.export();
            r.put("node", "n" + i);
            r.put("leader", d.getInfo().isLeader());
            r.put(
                "applied",
                machine.getLastAppliedTermIndex() == null
                    ? -1
                    : machine.getLastAppliedTermIndex().getIndex());
            byte[] b = r.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
          } finally {
            x.close();
          }
        });
    http.createContext(
        "/snapshot",
        x -> {
          try {
            byte[] b = ("{\"index\":" + machine.takeSnapshot() + "}").getBytes();
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
          } finally {
            x.close();
          }
        });
    http.start();
    http.createContext(
        "/fault",
        x -> {
          try {
            var c = KvState.JSON.readTree(x.getRequestBody());
            int source = c.path("target").asInt(-1);
            if (source < 0 || source > 2 || source == i) {
              x.sendResponseHeaders(400, -1);
              return;
            }
            proxy.configure(source, c.path("blocked").asBoolean());
            byte[] b = "{\"ok\":true}".getBytes();
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
          } finally {
            x.close();
          }
        });
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  http.stop(0);
                  try {
                    server.close();
                  } catch (Exception ignored) {
                  }
                }));
    new CountDownLatch(1).await();
  }
}
