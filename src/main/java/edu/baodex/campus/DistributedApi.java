package edu.baodex.campus;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("distributed")
@RequestMapping("/api")
public class DistributedApi extends Api {
  final KvGateway kv;
  final JdbcTemplate db;
  final Clock clock;

  public DistributedApi(JdbcTemplate db, CampusService s, Clock clock, KvGateway kv) {
    super(db, s, clock);
    this.db = db;
    this.clock = clock;
    this.kv = kv;
  }

  @PostMapping("/lab/kv")
  public JsonNode lab(@RequestBody JsonNode c, HttpServletRequest req) {
    admin(req);
    return kv.send(c);
  }

  @PostMapping("/lab/clock")
  public Map<String, Object> setClock(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    admin(req);
    ((AdjustableClock) clock).setOffset(CampusService.number(b, "seconds"));
    return Map.of("server_time", LocalDateTime.now(clock).toString());
  }

  @PostMapping("/lab/fault")
  public Map<String, Object> fault(@RequestBody JsonNode b, HttpServletRequest req)
      throws Exception {
    admin(req);
    int source = b.path("source").asInt(-1), target = b.path("target").asInt(-1);
    if (source < 0 || source > 2 || target < 0 || target > 2 || source == target)
      throw new Problem(400, "节点编号必须为0到2且不同");
    var r =
        java.net.http.HttpClient.newHttpClient()
            .send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + (19100 + target) + "/fault"))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .POST(
                        java.net.http.HttpRequest.BodyPublishers.ofString(
                            KvState.obj()
                                .put("target", source)
                                .put("blocked", b.path("blocked").asBoolean())
                                .toString()))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 200) throw new Problem(503, "故障控制节点不可用");
    return Map.of("ok", true);
  }

  @GetMapping("/lab/status")
  public List<Object> nodes(HttpServletRequest req) {
    admin(req);
    List<Object> out = new ArrayList<>();
    for (int i = 0; i < 3; i++)
      try {
        var url = java.net.URI.create("http://127.0.0.1:" + (19100 + i) + "/status");
        var response =
            java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(1))
                .build()
                .send(
                    java.net.http.HttpRequest.newBuilder(url)
                        .timeout(java.time.Duration.ofSeconds(1))
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
        out.add(KvState.JSON.readTree(response.body()));
      } catch (Exception e) {
        out.add(Map.of("node", "n" + i, "unavailable", true));
      }
    return out;
  }

  List<Map<String, Object>> maps(JsonNode n) {
    return KvState.JSON.convertValue(
        n, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
  }

  List<JsonNode> all() {
    List<JsonNode> out = new ArrayList<>();
    kv.scan("schedule:")
        .elements()
        .forEachRemaining(v -> v.path("bookings").elements().forEachRemaining(out::add));
    return out;
  }

  JsonNode find(long id) {
    return all().stream()
        .filter(b -> b.path("id").asLong() == id)
        .findFirst()
        .orElseThrow(() -> new Problem(404, "预约不存在"));
  }

  @Override
  @GetMapping("/venues")
  public List<Map<String, Object>> venues(HttpServletRequest req) {
    user(req);
    var out = KvState.JSON.createArrayNode();
    kv.scan("venue:").elements().forEachRemaining(out::add);
    return maps(out);
  }

  @PostMapping("/lab/init")
  public Map<String, Object> init(HttpServletRequest req) {
    admin(req);
    for (var v : db.queryForList("SELECT * FROM venue ORDER BY id")) {
      String key = "venue:" + v.get("id");
      var c =
          KvState.obj()
              .put("op", "CAS")
              .put("key", key)
              .put("expected", 0)
              .put("requestId", "init:" + key)
              .put("digest", key);
      c.set("value", KvState.JSON.valueToTree(v));
      kv.send(c);
    }
    return Map.of("ok", true);
  }

  @Override
  @GetMapping("/venues/{id}/schedule")
  public Map<String, Object> schedule(
      @PathVariable long id, @RequestParam String date, HttpServletRequest req) {
    user(req);
    LocalDate.parse(date);
    var a = KvState.JSON.createArrayNode();
    kv.get("schedule:" + id + ":" + date)
        .path("value")
        .path("bookings")
        .elements()
        .forEachRemaining(
            b -> {
              if (b.path("status").asText().equals("APPROVED")) a.add(b);
            });
    return Map.of("bookings", maps(a), "closures", List.of());
  }

  @Override
  @GetMapping("/bookings")
  public List<Map<String, Object>> bookings(HttpServletRequest req) {
    var s = user(req);
    var a = KvState.JSON.createArrayNode();
    all().stream()
        .filter(b -> s.role().equals("ADMIN") || b.path("applicant_id").asLong() == s.id())
        .sorted(Comparator.comparingLong((JsonNode n) -> n.path("id").asLong()).reversed())
        .forEach(a::add);
    return maps(a);
  }

  @Override
  @GetMapping("/bookings/{id}")
  public Map<String, Object> booking(@PathVariable long id, HttpServletRequest req) {
    var s = user(req);
    var b = find(id);
    if (!s.role().equals("ADMIN") && b.path("applicant_id").asLong() != s.id())
      throw new Problem(403, "只能查看本人预约");
    return Map.of("booking", KvState.JSON.convertValue(b, Map.class), "logs", List.of());
  }

  @GetMapping("/requests/{id}")
  public JsonNode request(@PathVariable String id, HttpServletRequest req) {
    var s = user(req);
    return kv.send(KvState.obj().put("op", "RESULT").put("key", s.id() + ":" + id));
  }

  @Override
  @PostMapping("/bookings")
  public Map<String, Object> apply(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    var s = user(req);
    if (!s.role().equals("APPLICANT")) throw new Problem(403, "请使用申请人账号");
    return mutate("APPLY", 0, b, req, s);
  }

  @Override
  @PostMapping("/bookings/{id}/approve")
  public Map<String, Object> approve(@PathVariable long id, HttpServletRequest req) {
    return mutate("APPROVE", id, Map.of(), req, admin(req));
  }

  @Override
  @PostMapping("/bookings/{id}/reject")
  public Map<String, Object> reject(
      @PathVariable long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
    return mutate("REJECT", id, b, req, admin(req));
  }

  @Override
  @PostMapping("/bookings/{id}/cancel")
  public Map<String, Object> cancel(
      @PathVariable long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
    return mutate("CANCEL", id, b, req, user(req));
  }

  Map<String, Object> mutate(
      String op, long id, Map<String, Object> body, HttpServletRequest req, Session actor) {
    String request = req.getHeader("X-Request-ID");
    if (request == null || !request.matches("[A-Za-z0-9_-]{1,100}"))
      throw new Problem(400, "请携带 X-Request-ID");
    String rid = actor.id() + ":" + request;
    var identity = KvState.obj().put("op", op).put("id", id).put("actor", actor.id());
    identity.set("body", KvState.JSON.valueToTree(new TreeMap<>(body)));
    String digest = KvGateway.digest(identity);
    for (int attempt = 0; attempt < 64; attempt++) {
      JsonNode previous = kv.send(KvState.obj().put("op", "RESULT").put("key", rid));
      if (!previous.path("code").asText().equals("NOT_FOUND")) {
        if (!previous.path("digest").asText().equals(digest))
          throw new Problem(409, "请求 ID 已用于其他操作");
        return finish(previous.path("result"));
      }
      var now = LocalDateTime.now(clock);
      JsonNode existing = op.equals("APPLY") ? null : find(id);
      long venue =
          op.equals("APPLY")
              ? CampusService.number(body, "venue_id")
              : existing.path("venue_id").asLong();
      LocalDateTime start =
          op.equals("APPLY")
              ? CampusService.time(body, "start_time")
              : LocalDateTime.parse(existing.path("start_time").asText());
      LocalDateTime end =
          op.equals("APPLY")
              ? CampusService.time(body, "end_time")
              : LocalDateTime.parse(existing.path("end_time").asText());
      String key = "schedule:" + venue + ":" + start.toLocalDate();
      JsonNode read = kv.get(key), value = read.path("value");
      ObjectNode day = value.isObject() ? value.deepCopy() : KvState.obj();
      if (!day.has("bookings")) day.set("bookings", KvState.JSON.createArrayNode());
      ArrayNode rows = (ArrayNode) day.get("bookings");
      ObjectNode target = null;
      for (JsonNode row : rows) if (row.path("id").asLong() == id) target = (ObjectNode) row;
      var updates = KvState.JSON.createArrayNode();
      ObjectNode answer = KvState.result("OK");
      try {
        JsonNode v = kv.get("venue:" + venue).path("value");
        CampusService.require(v.isObject(), "场所尚未初始化");
        if (op.equals("APPLY")) {
          long people = CampusService.number(body, "attendees");
          CampusService.require(!start.isBefore(now.plusHours(24)), "预约须提前24小时");
          CampusService.require(
              end.isAfter(start) && start.toLocalDate().equals(end.toLocalDate()),
              "预约须在同一天且结束晚于开始");
          CampusService.require(
              v.path("active").asBoolean() && people > 0 && people <= v.path("capacity").asLong(),
              "场所未开放或人数超限");
          CampusService.require(
              !start.toLocalTime().isBefore(LocalTime.parse(v.path("open_time").asText()))
                  && !end.toLocalTime().isAfter(LocalTime.parse(v.path("close_time").asText())),
              "超出开放时间");
          checkOverlap(rows, start, end);
          var counter = kv.get("meta:id");
          long next = counter.path("value").asLong(0) + 1;
          ObjectNode b =
              KvState.obj()
                  .put("id", next)
                  .put("venue_id", venue)
                  .put("applicant_id", actor.id())
                  .put("applicant_name", actor.name())
                  .put("venue_name", v.path("name").asText())
                  .put("location", v.path("location").asText())
                  .put("start_time", start.toString())
                  .put("end_time", end.toString())
                  .put("attendees", people)
                  .put("purpose", CampusService.text(body, "purpose", 500))
                  .put("status", "PENDING")
                  .put("created_at", now.toString())
                  .put("updated_at", now.toString());
          rows.add(b);
          answer.put("id", next);
          updates.add(
              KvState.obj()
                  .put("key", "meta:id")
                  .put("expected", counter.path("version").asLong())
                  .put("value", next));
        } else {
          if (target == null) throw new Problem(404, "预约不存在");
          String status = target.path("status").asText();
          if (op.equals("APPROVE")) {
            CampusService.require(status.equals("PENDING"), "仅待审核可批准");
            CampusService.require(start.isAfter(now), "预约已过期");
            checkOverlap(rows, start, end);
            target.put("status", "APPROVED");
            target.set("rate_snapshot", v.path("hourly_rate"));
          }
          if (op.equals("REJECT")) {
            CampusService.require(status.equals("PENDING"), "仅待审核可驳回");
            target.put("reason", CampusService.text(body, "reason", 500));
            target.put("status", "REJECTED");
          }
          if (op.equals("CANCEL")) {
            if (!actor.role().equals("ADMIN") && target.path("applicant_id").asLong() != actor.id())
              throw new Problem(403, "只能取消本人预约");
            CampusService.require(Set.of("PENDING", "APPROVED").contains(status), "当前状态不可取消");
            CampusService.require(
                actor.role().equals("ADMIN") || !now.isAfter(start.minusHours(2)), "不足两小时，请联系管理员");
            target.put("reason", CampusService.text(body, "reason", 500));
            target.put("status", "CANCELLED");
          }
          target.put("updated_at", now.toString());
        }
        updates.add(
            KvState.obj()
                .put("key", key)
                .put("expected", read.path("version").asLong())
                .set("value", day));
      } catch (Problem problem) {
        updates.removeAll();
        updates.add(
            KvState.obj()
                .put("key", key)
                .put("expected", read.path("version").asLong())
                .set("value", value));
        answer =
            KvState.result("BUSINESS_REJECTED")
                .put("status", problem.status)
                .put("message", problem.getMessage());
      }
      var command = KvState.obj().put("op", "BATCH").put("requestId", rid).put("digest", digest);
      command.set("updates", updates);
      command.set("answer", answer);
      var result = kv.send(command);
      if (result.path("code").asText().equals("VERSION_CONFLICT")) continue;
      if ("true".equals(req.getHeader("X-Lab-Drop-Response")))
        throw new Problem(503, "实验：提交已完成，响应被丢弃；请用相同请求 ID 重试");
      return finish(result);
    }
    throw new Problem(409, "并发冲突，请保留请求 ID 重试");
  }

  static void checkOverlap(ArrayNode rows, LocalDateTime start, LocalDateTime end) {
    for (JsonNode b : rows)
      if (b.path("status").asText().equals("APPROVED")
          && LocalDateTime.parse(b.path("start_time").asText()).isBefore(end)
          && LocalDateTime.parse(b.path("end_time").asText()).isAfter(start))
        throw new Problem(409, "同一场所的重叠时段已获批");
  }

  Map<String, Object> finish(JsonNode r) {
    if (r.path("code").asText().equals("BUSINESS_REJECTED"))
      throw new Problem(r.path("status").asInt(), r.path("message").asText());
    if (!r.path("code").asText().equals("OK")) throw new Problem(409, r.toString());
    return KvState.JSON.convertValue(r, Map.class);
  }

  @Override
  @GetMapping("/closures")
  public List<Map<String, Object>> closures(HttpServletRequest req) {
    admin(req);
    return List.of();
  }

  @Override
  @GetMapping("/audit")
  public List<Map<String, Object>> audit(HttpServletRequest req) {
    admin(req);
    return List.of();
  }
}
