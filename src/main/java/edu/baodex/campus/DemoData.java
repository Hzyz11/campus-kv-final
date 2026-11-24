package edu.baodex.campus;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;

@Component
public class DemoData implements ApplicationRunner {
 private final CampusService s;
 private final boolean seed;
 DemoData(CampusService s,@Value("${app.seed:true}") boolean seed) { this.s=s; this.seed=seed; }
 @Override @Transactional public void run(ApplicationArguments args) {
  if(!seed||s.jdbc().queryForObject("SELECT COUNT(*) FROM user_account",Long.class)>0) return;
  BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();
  long admin=s.insert("INSERT INTO user_account(username,password_hash,name,role) VALUES (?,?,?,'ADMIN')","admin",encoder.encode("Admin@12345"),"夏源");
  long student=s.insert("INSERT INTO user_account(username,password_hash,name,role) VALUES (?,?,?,'APPLICANT')","student",encoder.encode("Student@12345"),"林同学");
  long student2=s.insert("INSERT INTO user_account(username,password_hash,name,role) VALUES (?,?,?,'APPLICANT')","student2",encoder.encode("Student@12345"),"陈同学");
  long[] venues=new long[6];
  String[][] data={
   {"致远会议室","综合楼 · A 座 302","24","高清投影、白板、无线网络","会议室","0","适合社团例会、小组讨论与项目交流。安静明亮的空间，让想法在这里汇聚。"},
   {"知行活动室","学生活动中心 · 2 层 201","60","投影设备、音响、可移动桌椅","活动室","30","灵活布置的活动空间，适合工作坊、读书会和社团主题活动。"},
   {"星河多功能厅","学生活动中心 · 1 层","180","LED 大屏、舞台灯光、专业音响、无线麦克风","多功能厅","120","面向讲座、文艺汇演与大型交流活动，提供完整的舞台展示设施。"},
   {"青禾研讨室","图书馆 · 东区 405","12","交互屏、白板、电源插座","会议室","0","紧凑舒适的研讨空间，适合学习小组、策划讨论和项目复盘。"},
   {"创想工坊","创新楼 · B 座 103","36","投影设备、工作台、展示板","活动室","20","支持创意分享与动手实践，可按活动需求灵活调整桌椅。"},
   {"明德报告厅","教学楼 · C 座 1 层","120","高清投影、讲台、音响、无线麦克风","多功能厅","80","适合学术报告、经验分享与校内培训。请提前确认所需设备。"}
  };
  for(int i=0;i<data.length;i++) { var v=data[i]; venues[i]=s.insert("INSERT INTO venue(name,location,capacity,equipment,open_time,close_time,hourly_rate,category,description,active) VALUES (?,?,?,?,?,?,?,?,?,TRUE)",v[0],v[1],Integer.parseInt(v[2]),v[3],"08:00","22:00",new BigDecimal(v[5]),v[4],v[6]); }
  LocalDate today=s.now().toLocalDate();
  demo(student,venues[0],today.plusDays(2),14,16,18,"社团学期工作例会","PENDING",BigDecimal.ZERO,admin);
  demo(student2,venues[0],today.plusDays(2),15,17,20,"校园文化节筹备会","PENDING",BigDecimal.ZERO,admin);
  demo(student,venues[1],today.plusDays(3),14,16,40,"新媒体创作工作坊","APPROVED",new BigDecimal("30"),admin);
  demo(student2,venues[2],today.plusDays(4),18,20,100,"校园音乐分享会","APPROVED",new BigDecimal("120"),admin);
  demo(student,venues[3],today.minusDays(2),14,16,8,"读书交流与分享","COMPLETED",BigDecimal.ZERO,admin);
  demo(student2,venues[4],today.minusDays(1),9,11,20,"创意设计实践","COMPLETED",new BigDecimal("20"),admin);
  demo(student,venues[1],today.minusDays(1),14,16,24,"活动复盘交流","APPROVED",new BigDecimal("30"),admin);
  demo(student,venues[5],today.plusDays(5),14,16,50,"职业规划分享","REJECTED",new BigDecimal("80"),admin);
  demo(student2,venues[4],today.plusDays(6),14,16,16,"摄影社交流","CANCELLED",new BigDecimal("20"),admin);
  s.insert("INSERT INTO closure(venue_id,start_time,end_time,reason,created_by,active,created_at) VALUES (?,?,?,?,?,TRUE,?)",venues[5],today.plusDays(2).atTime(8,0),today.plusDays(2).atTime(18,0),"投影及音响设备检修",admin,s.now());
  s.log(null,admin,"初始化演示数据","已生成测试账号、6 个场所和预约样例；仅首次启动初始化");
 }
 void demo(long applicant,long venue,LocalDate date,int start,int end,int count,String purpose,String status,BigDecimal rate,long admin) {
  String reason=status.equals("REJECTED")?"请补充活动组织方案后重新申请":status.equals("CANCELLED")?"活动安排调整":null;
  long id=s.insert("INSERT INTO booking(applicant_id,venue_id,start_time,end_time,attendees,purpose,status,reason,rate_snapshot,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",applicant,venue,date.atTime(start,0),date.atTime(end,0),count,purpose,status,reason,rate,s.now().minusDays(5),s.now());
  s.log(id,applicant,"样例预约","演示数据 · "+purpose);
  if(status.equals("COMPLETED")) {
   s.insert("INSERT INTO usage_record(booking_id,actual_start,actual_end,registered_by,note) VALUES (?,?,?,?,?)",id,date.atTime(start,0),date.atTime(end,0),admin,"演示样例：活动正常结束");
   s.insert("INSERT INTO fee_record(booking_id,hourly_rate,minutes_used,amount,paid,confirmed_by,confirmed_at) VALUES (?,?,?,?,TRUE,?,?)",id,rate,(end-start)*60,rate.multiply(BigDecimal.valueOf(end-start)),admin,s.now());
  }
 }
}
