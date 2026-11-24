package edu.baodex.campus;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.seed=false"})
@AutoConfigureMockMvc
@Import(CampusTests.FixedTime.class)
class CampusTests {
 @TestConfiguration static class FixedTime { @Bean @Primary Clock testClock() { return Clock.fixed(Instant.parse("2026-11-17T06:00:00Z"),ZoneId.of("Asia/Shanghai")); } }
 @Autowired CampusService s;
 @Autowired MockMvc mvc;
 long applicant,other,admin,admin2,venue;
 final LocalDateTime now=LocalDateTime.of(2026,11,17,14,0);
 @BeforeEach void setup() {
  for(String t:List.of("audit_log","fee_record","usage_record","closure","booking","venue","user_account"))s.jdbc().update("DELETE FROM "+t);
  String hash=new BCryptPasswordEncoder(4).encode("Test@12345");
  applicant=s.insert("INSERT INTO user_account(username,password_hash,name,role) VALUES (?,?,?,'APPLICANT')","test",hash,"测试申请人");
  other=s.insert("INSERT INTO user_account(username,password_hash,name,role) VALUES (?,?,?,'APPLICANT')","other",hash,"另一申请人");
  admin=s.insert("INSERT INTO user_account(username,password_hash,name,role) VALUES (?,?,?,'ADMIN')","admin",hash,"管理员");
  admin2=s.insert("INSERT INTO user_account(username,password_hash,name,role) VALUES (?,?,?,'ADMIN')","admin2",hash,"管理员二");
  venue=s.insert("INSERT INTO venue(name,location,capacity,equipment,open_time,close_time,hourly_rate,category,description,active) VALUES ('测试会议室','A101',30,'投影','08:00','22:00',30,'会议室','测试',TRUE)");
 }
 Map<String,Object> request(LocalDateTime start,LocalDateTime end,int people) { return Map.of("venue_id",venue,"start_time",start.toString(),"end_time",end.toString(),"attendees",people,"purpose","测试活动"); }
 long apply(LocalDateTime start,LocalDateTime end) { return s.apply(request(start,end,10),applicant); }
 long seeded(LocalDateTime start,LocalDateTime end,String status) { return s.insert("INSERT INTO booking(applicant_id,venue_id,start_time,end_time,attendees,purpose,status,rate_snapshot,created_at,updated_at) VALUES (?,?,?,?,10,'测试',?,30,?,?)",applicant,venue,start,end,status,now.minusDays(2),now); }
 Cookie login(String name) throws Exception { var r=mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\""+name+"\",\"password\":\"Test@12345\"}")).andExpect(status().isOk()).andReturn();String c=r.getResponse().getHeader("Set-Cookie");return new Cookie("CAMPUS_SESSION",c.substring(c.indexOf('=')+1,c.indexOf(';'))); }
 @Test void applicationBoundaryAndCapacity() {
  assertDoesNotThrow(()->apply(now.plusHours(24),now.plusHours(26)));
  assertThrows(Problem.class,()->apply(now.plusHours(24).minusSeconds(1),now.plusHours(26)));
  assertThrows(Problem.class,()->s.apply(request(now.plusDays(2),now.plusDays(2).plusHours(1),31),applicant));
  assertThrows(Problem.class,()->s.apply(request(now.plusDays(2),now.plusDays(2).plusHours(1),0),applicant));
 }
 @Test void pendingDoesNotOccupyAndAdjacentAllowed() {
  long a=apply(now.plusDays(2),now.plusDays(2).plusHours(2));
  long b=apply(now.plusDays(2).plusHours(1),now.plusDays(2).plusHours(3));
  s.approve(a,admin);assertThrows(Problem.class,()->s.approve(b,admin));
  long c=apply(now.plusDays(2).plusHours(2),now.plusDays(2).plusHours(4));assertDoesNotThrow(()->s.approve(c,admin));
 }
 @Test void concurrentOverlappingApprovalsAreSerialized() throws Exception {
  long a=apply(now.plusDays(2),now.plusDays(2).plusHours(2));long b=apply(now.plusDays(2).plusHours(1),now.plusDays(2).plusHours(3));
  ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);
  try {
   var one=pool.submit(()->{gate.await();try{s.approve(a,admin);return true;}catch(Problem e){return false;}});
   var two=pool.submit(()->{gate.await();try{s.approve(b,admin2);return true;}catch(Problem e){return false;}});
   gate.countDown();assertEquals(1,(one.get(10,TimeUnit.SECONDS)?1:0)+(two.get(10,TimeUnit.SECONDS)?1:0));
   assertEquals(1L,s.jdbc().queryForObject("SELECT COUNT(*) FROM booking WHERE status='APPROVED'",Long.class));
  } finally {pool.shutdownNow();}
 }
 @Test void cancellationBoundaryAndRelease() {
  long exact=seeded(now.plusHours(2),now.plusHours(3),"APPROVED");
  s.cancel(exact,Map.of("reason","活动调整"),applicant,false);assertEquals("CANCELLED",s.booking(exact).get("status"));
  long late=seeded(now.plusHours(2).minusSeconds(1),now.plusHours(3),"APPROVED");assertThrows(Problem.class,()->s.cancel(late,Map.of("reason","调整"),applicant,false));
  assertDoesNotThrow(()->s.cancel(late,Map.of("reason","管理员处理逾期取消"),admin,true));
 }
 @Test void approvalCancellationReleasesForAnotherApplication() {
  long a=apply(now.plusDays(2),now.plusDays(2).plusHours(2)),b=apply(now.plusDays(2),now.plusDays(2).plusHours(2));
  s.approve(a,admin);s.cancel(a,Map.of("reason","取消活动"),applicant,false);assertDoesNotThrow(()->s.approve(b,admin));
 }
 @Test void closuresRequireReviewAndBlockApproval() {
  long a=apply(now.plusDays(2),now.plusDays(2).plusHours(2));s.approve(a,admin);
  var closure=new HashMap<String,Object>(Map.of("venue_id",venue,"start_time",now.plusDays(2).toString(),"end_time",now.plusDays(2).plusHours(4).toString(),"reason","检修"));
  assertEquals(1,s.affected(closure).size());assertThrows(Problem.class,()->s.addClosure(closure,admin));
  closure.put("cancel_affected",true);long c=s.addClosure(closure,admin);assertEquals("CANCELLED",s.booking(a).get("status"));
  assertThrows(Problem.class,()->apply(now.plusDays(2),now.plusDays(2).plusHours(1)));
  s.reopen(c,admin);assertDoesNotThrow(()->apply(now.plusDays(2),now.plusDays(2).plusHours(1)));
 }
 @Test void rateSnapshotActualUsageAndPayment() {
  long b=seeded(now.minusHours(3),now.minusHours(1),"APPROVED");s.jdbc().update("UPDATE venue SET hourly_rate=200 WHERE id=?",venue);
  s.start(b,Map.of("actual_start",now.minusHours(3).toString()),admin);
  s.finish(b,Map.of("actual_end",now.minusMinutes(59).toString(),"note","正常"),admin);
  var f=s.one("SELECT * FROM fee_record WHERE booking_id=?",b);assertEquals(new BigDecimal("60.50"),f.get("amount"));
  assertEquals(121L,CampusService.id(f,"minutes_used"));assertEquals(false,f.get("paid"));s.paid(b,admin);
  assertEquals(true,s.one("SELECT * FROM fee_record WHERE booking_id=?",b).get("paid"));assertThrows(Problem.class,()->s.paid(b,admin));
 }
 @Test void usageRejectsInvalidDatesAndFreeHasZeroFee() {
  long b=seeded(now.minusHours(2),now.minusHours(1),"APPROVED");s.jdbc().update("UPDATE booking SET rate_snapshot=0 WHERE id=?",b);
  assertThrows(Problem.class,()->s.start(b,Map.of("actual_start",now.plusHours(1).toString()),admin));
  assertThrows(Problem.class,()->s.start(b,Map.of("actual_start",now.minusDays(1).toString()),admin));
  s.start(b,Map.of("actual_start",now.minusHours(2).toString()),admin);
  assertThrows(Problem.class,()->s.finish(b,Map.of("actual_end",now.plusHours(1).toString()),admin));
  assertThrows(Problem.class,()->s.finish(b,Map.of("actual_end",now.minusHours(3).toString()),admin));
  s.finish(b,Map.of("actual_end",now.minusHours(1).toString()),admin);
  var f=s.one("SELECT * FROM fee_record WHERE booking_id=?",b);assertEquals(new BigDecimal("0.00"),f.get("amount"));assertEquals(true,f.get("paid"));
 }
 @Test void ownershipAndAdminApiEnforced() throws Exception {
  long b=apply(now.plusDays(2),now.plusDays(2).plusHours(1));Cookie otherCookie=login("other"),studentCookie=login("test");
  mvc.perform(get("/api/bookings/"+b).cookie(otherCookie)).andExpect(status().isForbidden());
  mvc.perform(post("/api/bookings/"+b+"/cancel").cookie(otherCookie).contentType("application/json").content("{\"reason\":\"越权\"}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/bookings/"+b+"/approve").cookie(studentCookie)).andExpect(status().isForbidden());
  mvc.perform(get("/api/stats?from=2026-11-01&to=2026-12-01").cookie(studentCookie)).andExpect(status().isForbidden());
  mvc.perform(get("/api/bookings").cookie(otherCookie)).andExpect(content().json("[]"));
  mvc.perform(get("/api/venues")).andExpect(status().isUnauthorized());
 }
 @Test void loginLogoutAndOriginProtection() throws Exception {
  mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"test\",\"password\":\"bad\"}")).andExpect(status().isUnauthorized());
  Cookie c=login("test");mvc.perform(post("/api/bookings").cookie(c).header("Origin","https://another.example").header("Host","localhost").contentType("application/json").content("{}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/auth/logout").cookie(c)).andExpect(status().isOk());mvc.perform(get("/api/auth/me").cookie(c)).andExpect(status().isUnauthorized());
 }
 @Test void statsAreDateAndVenueScoped() {
  seeded(now.minusDays(1),now.minusDays(1).plusHours(1),"CANCELLED");seeded(now.plusDays(3),now.plusDays(3).plusHours(1),"PENDING");
  var stats=s.stats(now.toLocalDate().minusDays(1),now.toLocalDate(),venue);assertEquals(1,stats.get("total"));assertEquals(1L,stats.get("cancelled"));
  assertEquals(0,s.stats(now.toLocalDate().minusDays(1),now.toLocalDate(),venue+100).get("total"));
 }
 @Test void openingHoursAndInvalidVenueChanges() {
  assertThrows(Problem.class,()->apply(now.plusDays(2).withHour(7),now.plusDays(2).withHour(9)));
  assertThrows(Problem.class,()->apply(now.plusDays(2).withHour(21),now.plusDays(2).withHour(23)));
  long b=apply(now.plusDays(2),now.plusDays(2).plusHours(1));s.approve(b,admin);
  var v=new HashMap<>(s.venue(venue));v.put("capacity",5);assertThrows(Problem.class,()->s.saveVenue(venue,v,admin));
  v.put("capacity",30);v.put("active",false);assertThrows(Problem.class,()->s.saveVenue(venue,v,admin));
 }
}
