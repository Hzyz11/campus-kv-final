package edu.baodex.campus;

import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@org.springframework.context.annotation.Profile("!distributed")
@RequestMapping("/api")
public class Api {
 private final JdbcTemplate db;
 private final CampusService service;
 private final Clock clock;
 private final Map<String, Session> sessions = new ConcurrentHashMap<>();
 private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
 record Session(long id, String name, String role, Instant expires) {}
 record Attempt(int count, Instant expires) {}
 public Api(JdbcTemplate db, CampusService service, Clock clock) { this.db=db; this.service=service; this.clock=clock; }

 private Map<String,Object> publicUser(Session s) { return Map.of("id",s.id(),"name",s.name(),"role",s.role(),"server_time",LocalDateTime.now(clock)); }
 Session user(HttpServletRequest req) {
  String token=Arrays.stream(Optional.ofNullable(req.getCookies()).orElse(new Cookie[0]))
   .filter(c->c.getName().equals("CAMPUS_SESSION")).map(Cookie::getValue).findFirst().orElse("");
  Session s=sessions.get(token);
  if(s==null || !s.expires().isAfter(clock.instant())) { sessions.remove(token); throw new Problem(401,"请先登录，或重新登录已过期的账号"); }
  if(!req.getMethod().equals("GET")) sameOrigin(req);
  return s;
 }
 private void sameOrigin(HttpServletRequest req) {
  String origin=req.getHeader("Origin");
  if(origin!=null) {
   try { if(!URI.create(origin).getRawAuthority().equalsIgnoreCase(req.getHeader("Host"))) throw new Problem(403,"请求来源不匹配"); }
   catch(IllegalArgumentException e) { throw new Problem(403,"请求来源无效"); }
  }
 }
 Session admin(HttpServletRequest req) { Session s=user(req); if(!s.role().equals("ADMIN")) throw new Problem(403,"此操作需要管理员权限"); return s; }

 @PostMapping("/auth/login") public Map<String,Object> login(@RequestBody Map<String,Object> body,HttpServletRequest req,HttpServletResponse res) {
  sameOrigin(req);
  String account=CampusService.text(body,"username",64), password=CampusService.text(body,"password",100);
  String key=req.getRemoteAddr()+":"+account;
  Attempt a=attempts.get(key);
  if(a!=null && a.expires().isAfter(clock.instant()) && a.count()>=10) throw new Problem(429,"登录尝试过多，请 10 分钟后重试");
  List<Map<String,Object>> rows=db.queryForList("SELECT * FROM user_account WHERE username=?",account);
  if(rows.isEmpty() || !new BCryptPasswordEncoder().matches(password,rows.get(0).get("password_hash").toString())) {
   attempts.put(key,new Attempt(a!=null&&a.expires().isAfter(clock.instant())?a.count()+1:1,clock.instant().plusSeconds(600)));
   throw new Problem(401,"账号或密码错误");
  }
  attempts.remove(key); Map<String,Object> r=rows.get(0);
  sessions.entrySet().removeIf(e->!e.getValue().expires().isAfter(clock.instant()));
  if(req.getCookies()!=null) for(Cookie c:req.getCookies()) if(c.getName().equals("CAMPUS_SESSION")) sessions.remove(c.getValue());
  String token=UUID.randomUUID()+"-"+UUID.randomUUID();
  Session s=new Session(((Number)r.get("id")).longValue(),r.get("name").toString(),r.get("role").toString(),clock.instant().plusSeconds(28800));
  sessions.put(token,s); res.addHeader("Set-Cookie",ResponseCookie.from("CAMPUS_SESSION",token).httpOnly(true).secure(req.isSecure()).sameSite("Strict").path("/").maxAge(28800).build().toString());
  return publicUser(s);
 }
 @GetMapping("/auth/me") public Map<String,Object> me(HttpServletRequest req) { return publicUser(user(req)); }
 @PostMapping("/auth/logout") public Map<String,Object> logout(HttpServletRequest req,HttpServletResponse res) {
  user(req); if(req.getCookies()!=null) for(Cookie c:req.getCookies()) if(c.getName().equals("CAMPUS_SESSION")) sessions.remove(c.getValue());
  res.addHeader("Set-Cookie",ResponseCookie.from("CAMPUS_SESSION","").httpOnly(true).sameSite("Strict").path("/").maxAge(0).build().toString()); return Map.of("ok",true);
 }
 @GetMapping("/venues") public List<Map<String,Object>> venues(HttpServletRequest req) { user(req); return db.queryForList("SELECT * FROM venue ORDER BY id"); }
 @GetMapping("/venues/{id}/schedule") public Map<String,Object> schedule(@PathVariable long id,@RequestParam String date,HttpServletRequest req) {
  user(req); LocalDate d=LocalDate.parse(date); service.venue(id);
  return Map.of("bookings",db.queryForList("SELECT id,start_time,end_time,status FROM booking WHERE venue_id=? AND status IN ('APPROVED','IN_USE','COMPLETED') AND start_time<? AND end_time>? ORDER BY start_time",id,d.plusDays(1).atStartOfDay(),d.atStartOfDay()),
   "closures",db.queryForList("SELECT id,start_time,end_time,reason FROM closure WHERE venue_id=? AND active=TRUE AND start_time<? AND end_time>? ORDER BY start_time",id,d.plusDays(1).atStartOfDay(),d.atStartOfDay()));
 }
 @PostMapping("/venues") public Map<String,Object> addVenue(@RequestBody Map<String,Object> b,HttpServletRequest req) { return Map.of("id",service.saveVenue(null,b,admin(req).id())); }
 @PutMapping("/venues/{id}") public Map<String,Object> editVenue(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest req) { return Map.of("id",service.saveVenue(id,b,admin(req).id())); }
 @GetMapping("/closures") public List<Map<String,Object>> closures(HttpServletRequest req) { admin(req); return db.queryForList("SELECT c.*,v.name AS venue_name FROM closure c JOIN venue v ON v.id=c.venue_id ORDER BY c.start_time DESC"); }
 @PostMapping("/closures/preview") public List<Map<String,Object>> closurePreview(@RequestBody Map<String,Object> b,HttpServletRequest req) { admin(req); return service.affected(b); }
 @PostMapping("/closures") public Map<String,Object> addClosure(@RequestBody Map<String,Object> b,HttpServletRequest req) { return Map.of("id",service.addClosure(b,admin(req).id())); }
 @PostMapping("/closures/{id}/reopen") public Map<String,Object> reopen(@PathVariable long id,HttpServletRequest req) { service.reopen(id,admin(req).id()); return Map.of("ok",true); }
 @GetMapping("/bookings") public List<Map<String,Object>> bookings(HttpServletRequest req) { Session s=user(req); return service.bookings(s.role().equals("ADMIN")?null:s.id()); }
 @GetMapping("/bookings/{id}") public Map<String,Object> booking(@PathVariable long id,HttpServletRequest req) {
  Session s=user(req); Map<String,Object> b=service.booking(id); if(!s.role().equals("ADMIN")&&((Number)b.get("applicant_id")).longValue()!=s.id()) throw new Problem(403,"只能查看本人的预约");
  return Map.of("booking",service.bookings(s.role().equals("ADMIN")?null:s.id()).stream().filter(r->((Number)r.get("id")).longValue()==id).findFirst().orElseThrow(),
   "logs",db.queryForList("SELECT a.action,a.detail,a.created_at,u.name AS actor_name FROM audit_log a JOIN user_account u ON u.id=a.actor_id WHERE booking_id=? ORDER BY a.id",id));
 }
 @PostMapping("/bookings") public Map<String,Object> apply(@RequestBody Map<String,Object> b,HttpServletRequest req) { Session s=user(req); if(!s.role().equals("APPLICANT")) throw new Problem(403,"请使用申请人账号提交预约"); return Map.of("id",service.apply(b,s.id())); }
 @PostMapping("/bookings/{id}/approve") public Map<String,Object> approve(@PathVariable long id,HttpServletRequest req) { service.approve(id,admin(req).id()); return Map.of("ok",true); }
 @PostMapping("/bookings/{id}/reject") public Map<String,Object> reject(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest req) { service.reject(id,CampusService.text(b,"reason",500),admin(req).id()); return Map.of("ok",true); }
 @PostMapping("/bookings/{id}/cancel") public Map<String,Object> cancel(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest req) { Session s=user(req); service.cancel(id,b,s.id(),s.role().equals("ADMIN")); return Map.of("ok",true); }
 @PostMapping("/bookings/{id}/start") public Map<String,Object> start(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest req) { service.start(id,b,admin(req).id()); return Map.of("ok",true); }
 @PostMapping("/bookings/{id}/finish") public Map<String,Object> finish(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest req) { service.finish(id,b,admin(req).id()); return Map.of("ok",true); }
 @PostMapping("/bookings/{id}/paid") public Map<String,Object> paid(@PathVariable long id,HttpServletRequest req) { service.paid(id,admin(req).id()); return Map.of("ok",true); }
 @GetMapping("/stats") public Map<String,Object> stats(@RequestParam String from,@RequestParam String to,@RequestParam(required=false) Long venueId,HttpServletRequest req) { admin(req); return service.stats(LocalDate.parse(from),LocalDate.parse(to),venueId); }
 @GetMapping("/audit") public List<Map<String,Object>> audit(HttpServletRequest req) { admin(req); return db.queryForList("SELECT a.*,u.name AS actor_name FROM audit_log a JOIN user_account u ON u.id=a.actor_id ORDER BY a.id DESC LIMIT 200"); }
}

class Problem extends RuntimeException {
 final int status;
 Problem(int status,String message) { super(message); this.status=status; }
}

@RestControllerAdvice
class Errors {
 @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class) ResponseEntity<?> missing(Exception e) { return ResponseEntity.status(404).body(Map.of("message","资源不存在")); }
 @ExceptionHandler(Problem.class) ResponseEntity<?> business(Problem e) { return ResponseEntity.status(e.status).body(Map.of("message",e.getMessage())); }
 @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
 ResponseEntity<?> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("message","输入格式有误，请检查日期、时间及数字")); }
 @ExceptionHandler(ConcurrencyFailureException.class) ResponseEntity<?> conflict(Exception e) { return ResponseEntity.status(409).body(Map.of("message","记录正在被其他管理员处理，请刷新后重试")); }
 @ExceptionHandler(Exception.class) ResponseEntity<?> other(Exception e) { org.slf4j.LoggerFactory.getLogger(Errors.class).error("Request failed",e); return ResponseEntity.internalServerError().body(Map.of("message","操作未完成，请刷新后重试或联系管理员")); }
}
