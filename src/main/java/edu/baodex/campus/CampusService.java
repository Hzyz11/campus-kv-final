package edu.baodex.campus;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

@Service
public class CampusService {
 final JdbcTemplate db;
 final Clock clock;
 public CampusService(JdbcTemplate db,Clock clock) { this.db=db; this.clock=clock; }
 public JdbcTemplate jdbc() { return db; }
 LocalDateTime now() { return LocalDateTime.now(clock); }
 static String text(Map<String,Object> b,String key,int max) {
  Object value=b.get(key); String s=value==null?"":value.toString().trim();
  if(s.isEmpty()||s.length()>max) throw new Problem(400,"请填写"+label(key)+"，长度不超过 "+max+" 字"); return s;
 }
 static String label(String k) { return Map.ofEntries(Map.entry("username","账号"),Map.entry("password","密码"),Map.entry("reason","原因"),Map.entry("name","名称"),Map.entry("location","位置"),Map.entry("equipment","设备"),Map.entry("description","说明"),Map.entry("purpose","活动用途"),Map.entry("category","类型")).getOrDefault(k,k); }
 static long number(Map<String,Object> b,String key) { try { return new BigDecimal(String.valueOf(b.get(key))).longValueExact(); } catch(Exception e) { throw new Problem(400,label(key)+"必须为整数"); } }
 static LocalDateTime time(Map<String,Object> b,String key) { return LocalDateTime.parse(text(b,key,40)); }
 static LocalDateTime dt(Object t) { return t instanceof Timestamp x?x.toLocalDateTime():LocalDateTime.parse(t.toString()); }
 static long id(Map<String,Object> r,String k) { return ((Number)r.get(k)).longValue(); }
 static void require(boolean ok,String message) { if(!ok) throw new Problem(400,message); }
 Map<String,Object> one(String sql,Object...args) { var rows=db.queryForList(sql,args); if(rows.isEmpty()) throw new Problem(404,"记录不存在"); return rows.get(0); }
 public Map<String,Object> venue(long id) { return one("SELECT * FROM venue WHERE id=?",id); }
 public Map<String,Object> booking(long id) { return one("SELECT * FROM booking WHERE id=?",id); }
 void lockVenue(long id) { one("SELECT id FROM venue WHERE id=? FOR UPDATE",id); }
 Map<String,Object> lockBooking(long id) { Map<String,Object> b=booking(id); lockVenue(id(b,"venue_id")); return one("SELECT * FROM booking WHERE id=? FOR UPDATE",id); }
 long insert(String sql,Object...args) {
  KeyHolder keys=new GeneratedKeyHolder();
  db.update(c->{ PreparedStatement p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS); for(int i=0;i<args.length;i++) p.setObject(i+1,args[i]); return p; },keys);
  return Objects.requireNonNull(keys.getKey()).longValue();
 }
 void log(Long bookingId,long actor,String action,String detail) { db.update("INSERT INTO audit_log(booking_id,actor_id,action,detail,created_at) VALUES (?,?,?,?,?)",bookingId,actor,action,detail,now()); }
 void range(LocalDateTime start,LocalDateTime end) { require(end.isAfter(start),"结束时间必须晚于开始时间"); }
 void validSlot(Map<String,Object> v,LocalDateTime start,LocalDateTime end,long attendees) {
  require(Boolean.TRUE.equals(v.get("active")),"该场所已停用"); range(start,end);
  require(start.toLocalDate().equals(end.toLocalDate()),"首版预约须在同一天内完成");
  require(attendees>0&&attendees<=id(v,"capacity"),"人数必须为正整数，且不超过场所容量（"+v.get("capacity")+" 人）");
  require(!start.toLocalTime().isBefore(LocalTime.parse(v.get("open_time").toString()))&&!end.toLocalTime().isAfter(LocalTime.parse(v.get("close_time").toString())),"预约时间超出场所开放时间");
  require(db.queryForObject("SELECT COUNT(*) FROM closure WHERE venue_id=? AND active=TRUE AND start_time<? AND end_time>?",Long.class,id(v,"id"),end,start)==0,"所选时段处于维修或停用期间");
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public long apply(Map<String,Object> b,long actor) {
  long venueId=number(b,"venue_id"); lockVenue(venueId); var v=venue(venueId);
  LocalDateTime start=time(b,"start_time"),end=time(b,"end_time"); long attendees=number(b,"attendees");
  require(!start.isBefore(now().plusHours(24)),"预约须至少提前 24 小时提交"); validSlot(v,start,end,attendees);
  require(db.queryForObject("SELECT COUNT(*) FROM booking WHERE venue_id=? AND status IN ('APPROVED','IN_USE','COMPLETED') AND start_time<? AND end_time>?",Long.class,venueId,end,start)==0,"所选时段已被占用，请选择其他时间");
  String purpose=text(b,"purpose",500);
  long id=insert("INSERT INTO booking(applicant_id,venue_id,start_time,end_time,attendees,purpose,status,created_at,updated_at) VALUES (?,?,?,?,?,?,'PENDING',?,?)",actor,venueId,start,end,attendees,purpose,now(),now());
  log(id,actor,"提交申请","已提交预约，等待管理员审核；待审核不占用时段"); return id;
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public void approve(long id,long actor) {
  var b=lockBooking(id); require(b.get("status").equals("PENDING"),"仅待审核预约可以批准");
  LocalDateTime start=dt(b.get("start_time")),end=dt(b.get("end_time")); require(start.isAfter(now()),"预约开始时间已过，请驳回或取消");
  var v=venue(id(b,"venue_id")); validSlot(v,start,end,id(b,"attendees"));
  require(db.queryForObject("SELECT COUNT(*) FROM booking WHERE venue_id=? AND id<>? AND status IN ('APPROVED','IN_USE','COMPLETED') AND start_time<? AND end_time>?",Long.class,id(b,"venue_id"),id,end,start)==0,"审批失败：同一场所的重叠时段已获批，请刷新日程");
  db.update("UPDATE booking SET status='APPROVED',reason=NULL,rate_snapshot=?,updated_at=? WHERE id=?",v.get("hourly_rate"),now(),id);
  log(id,actor,"审批通过","已批准并锁定预约时段，费率快照：¥"+v.get("hourly_rate")+"/小时");
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public void reject(long id,String reason,long actor) {
  var b=lockBooking(id); require(b.get("status").equals("PENDING"),"仅待审核预约可以驳回");
  db.update("UPDATE booking SET status='REJECTED',reason=?,updated_at=? WHERE id=?",reason,now(),id); log(id,actor,"驳回申请",reason);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public void cancel(long id,Map<String,Object> body,long actor,boolean admin) {
  var b=lockBooking(id);
  if(!admin&&id(b,"applicant_id")!=actor) throw new Problem(403,"只能取消本人的预约");
  require(List.of("PENDING","APPROVED").contains(b.get("status")),"当前状态不允许取消");
  if(!admin) require(!now().isAfter(dt(b.get("start_time")).minusHours(2)),"已超过自助取消截止时间，请联系管理员");
  String reason=text(body,"reason",500);
  db.update("UPDATE booking SET status='CANCELLED',reason=?,updated_at=? WHERE id=?",reason,now(),id); log(id,actor,admin?"管理员取消":"申请人取消",reason+"；已释放原占用时段");
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public long saveVenue(Long venueId,Map<String,Object> b,long actor) {
  String name=text(b,"name",100),loc=text(b,"location",160),equipment=text(b,"equipment",500),category=text(b,"category",40),desc=text(b,"description",1000);
  long cap=number(b,"capacity"); require(cap>0&&cap<=100000,"场所容量应为 1 到 100000 的整数");
  LocalTime open=LocalTime.parse(text(b,"open_time",5)),close=LocalTime.parse(text(b,"close_time",5)); require(close.isAfter(open),"关闭时间必须晚于开放时间");
  BigDecimal rate=new BigDecimal(text(b,"hourly_rate",20)); require(rate.signum()>=0&&rate.compareTo(new BigDecimal("999999.99"))<=0&&rate.scale()<=2,"小时费率须为非负金额，最多两位小数");
  boolean active=!Boolean.FALSE.equals(b.get("active"));
  if(venueId==null) {
   long id=insert("INSERT INTO venue(name,location,capacity,equipment,open_time,close_time,hourly_rate,category,description,active) VALUES (?,?,?,?,?,?,?,?,?,?)",name,loc,cap,equipment,open.toString(),close.toString(),rate,category,desc,active);
   log(null,actor,"新增场所",name); return id;
  }
  lockVenue(venueId);
  var occupied=db.queryForList("SELECT * FROM booking WHERE venue_id=? AND status IN ('APPROVED','IN_USE') AND end_time>?",venueId,now());
  for(var r:occupied) require(active&&id(r,"attendees")<=cap&&!dt(r.get("start_time")).toLocalTime().isBefore(open)&&!dt(r.get("end_time")).toLocalTime().isAfter(close),"变更将影响已有获批预约，请先处理受影响预约");
  db.update("UPDATE venue SET name=?,location=?,capacity=?,equipment=?,open_time=?,close_time=?,hourly_rate=?,category=?,description=?,active=? WHERE id=?",name,loc,cap,equipment,open.toString(),close.toString(),rate,category,desc,active,venueId);
  log(null,actor,"更新场所",name+"；已批准预约保留原费率"); return venueId;
 }
 public List<Map<String,Object>> affected(Map<String,Object> b) {
  long venueId=number(b,"venue_id"); venue(venueId); LocalDateTime start=time(b,"start_time"),end=time(b,"end_time"); range(start,end);
  return db.queryForList("SELECT b.id,b.status,b.start_time,b.end_time,b.purpose,u.name AS applicant_name FROM booking b JOIN user_account u ON u.id=b.applicant_id WHERE venue_id=? AND status IN ('PENDING','APPROVED','IN_USE') AND start_time<? AND end_time>? ORDER BY start_time",venueId,end,start);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public long addClosure(Map<String,Object> b,long actor) {
  long venueId=number(b,"venue_id"); lockVenue(venueId); var affected=affected(b); String reason=text(b,"reason",500);
  require(affected.stream().noneMatch(r->r.get("status").equals("IN_USE")),"有正在使用的预约，请先处理并结束使用，再设置停用");
  require(affected.isEmpty()||Boolean.TRUE.equals(b.get("cancel_affected")),"停用影响现有预约，请先核对清单并确认取消受影响预约");
  LocalDateTime start=time(b,"start_time"),end=time(b,"end_time"); require(end.isAfter(now()),"停用结束时间应晚于当前时间");
  for(var r:affected) {
   db.update("UPDATE booking SET status='CANCELLED',reason=?,updated_at=? WHERE id=?","场所停用："+reason,now(),r.get("id"));
   log(id(r,"id"),actor,"停用取消预约","场所停用："+reason);
  }
  long id=insert("INSERT INTO closure(venue_id,start_time,end_time,reason,created_by,active,created_at) VALUES (?,?,?,?,?,TRUE,?)",venueId,start,end,reason,actor,now());
  log(null,actor,"新增停用",venue(venueId).get("name")+"；"+start+" 至 "+end+"；"+reason); return id;
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public void reopen(long id,long actor) {
  var c=one("SELECT * FROM closure WHERE id=?",id); lockVenue(id(c,"venue_id"));
  require(db.update("UPDATE closure SET active=FALSE WHERE id=? AND active=TRUE",id)==1,"该停用记录已解除");
  log(null,actor,"解除停用","停用记录 #"+id+"；被取消的预约需重新申请，不会自动恢复");
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public void start(long id,Map<String,Object> body,long actor) {
  var b=lockBooking(id); require(b.get("status").equals("APPROVED"),"仅已批准预约可以登记到场");
  LocalDateTime start=time(body,"actual_start"); require(!start.isAfter(now()),"实际到场时间不能晚于当前时间");
  require(start.toLocalDate().equals(dt(b.get("start_time")).toLocalDate()),"到场日期须与预约日期一致");
  require(db.queryForObject("SELECT COUNT(*) FROM booking WHERE venue_id=? AND status='IN_USE'",Long.class,id(b,"venue_id"))==0,"该场所存在尚未结束的使用记录");
  insert("INSERT INTO usage_record(booking_id,actual_start,registered_by,note) VALUES (?,?,?,?)",id,start,actor,optionalNote(body));
  db.update("UPDATE booking SET status='IN_USE',updated_at=? WHERE id=?",now(),id); log(id,actor,"登记到场","实际到场："+start);
 }
 static String optionalNote(Map<String,Object> body) { String s=String.valueOf(body.getOrDefault("note","")).trim(); require(s.length()<=500,"备注不能超过 500 字"); return s; }
 @Transactional(isolation=Isolation.READ_COMMITTED) public void finish(long id,Map<String,Object> body,long actor) {
  var b=lockBooking(id); require(b.get("status").equals("IN_USE"),"仅使用中预约可以登记结束");
  var usage=one("SELECT * FROM usage_record WHERE booking_id=?",id); LocalDateTime end=time(body,"actual_end"),start=dt(usage.get("actual_start"));
  range(start,end); require(!end.isAfter(now()),"实际结束时间不能晚于当前时间");
  long minutes=Duration.between(start,end).toMinutes(); require(minutes>=1,"实际使用时长至少为 1 分钟");
  BigDecimal rate=(BigDecimal)b.get("rate_snapshot"); BigDecimal amount=rate.multiply(BigDecimal.valueOf(minutes)).divide(BigDecimal.valueOf(60),2,RoundingMode.HALF_UP);
  db.update("UPDATE usage_record SET actual_end=?,note=?,registered_by=? WHERE booking_id=?",end,optionalNote(body),actor,id);
  insert("INSERT INTO fee_record(booking_id,hourly_rate,minutes_used,amount,paid) VALUES (?,?,?,?,?)",id,rate,minutes,amount,amount.signum()==0);
  db.update("UPDATE booking SET status='COMPLETED',updated_at=? WHERE id=?",now(),id); log(id,actor,"登记结束","实际使用 "+minutes+" 分钟；费用 ¥"+amount+"；费率 ¥"+rate+"/小时");
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public void paid(long id,long actor) {
  lockBooking(id); var fee=one("SELECT * FROM fee_record WHERE booking_id=?",id); require(!Boolean.TRUE.equals(fee.get("paid")),"该记录已缴费或为免费场所");
  db.update("UPDATE fee_record SET paid=TRUE,confirmed_by=?,confirmed_at=? WHERE booking_id=?",actor,now(),id); log(id,actor,"确认线下缴费","确认收到 ¥"+fee.get("amount"));
 }
 public List<Map<String,Object>> bookings(Long applicant) {
  String sql="SELECT b.*,v.name AS venue_name,v.location,u.name AS applicant_name,ur.actual_start,ur.actual_end,ur.note,f.amount,f.minutes_used,f.paid,f.confirmed_at FROM booking b JOIN venue v ON v.id=b.venue_id JOIN user_account u ON u.id=b.applicant_id LEFT JOIN usage_record ur ON ur.booking_id=b.id LEFT JOIN fee_record f ON f.booking_id=b.id";
  return applicant==null?db.queryForList(sql+" ORDER BY b.id DESC"):db.queryForList(sql+" WHERE b.applicant_id=? ORDER BY b.id DESC",applicant);
 }
 public Map<String,Object> stats(LocalDate from,LocalDate to,Long venueId) {
  require(!to.isBefore(from),"统计结束日期不能早于开始日期");
  List<Map<String,Object>> rows=db.queryForList("SELECT b.*,v.name AS venue_name,f.amount,f.minutes_used,f.paid FROM booking b JOIN venue v ON v.id=b.venue_id LEFT JOIN fee_record f ON f.booking_id=b.id WHERE b.start_time>=? AND b.start_time<?"+(venueId==null?"":" AND b.venue_id=?"),venueId==null?new Object[]{from.atStartOfDay(),to.plusDays(1).atStartOfDay()}:new Object[]{from.atStartOfDay(),to.plusDays(1).atStartOfDay(),venueId});
  long cancelled=0,completed=0,minutes=0; BigDecimal amount=BigDecimal.ZERO,paid=BigDecimal.ZERO;
  Map<String,long[]> groups=new LinkedHashMap<>();
  for(var b:rows) {
   if(b.get("status").equals("CANCELLED"))cancelled++;
   long[] g=groups.computeIfAbsent(b.get("venue_name").toString(),k->new long[3]); g[0]++;
   if(b.get("status").equals("COMPLETED")) { completed++;g[1]++; minutes+=id(b,"minutes_used");g[2]+=id(b,"minutes_used"); BigDecimal a=(BigDecimal)b.get("amount"); amount=amount.add(a);if(Boolean.TRUE.equals(b.get("paid")))paid=paid.add(a); }
  }
  List<Map<String,Object>> perVenue=new ArrayList<>(); groups.forEach((k,v)->perVenue.add(Map.of("name",k,"bookings",v[0],"completed",v[1],"minutes",v[2])));
  return Map.of("total",rows.size(),"cancelled",cancelled,"completed",completed,"minutes",minutes,"amount",amount,"paid",paid,"venues",perVenue,"from",from,"to",to);
 }
}
