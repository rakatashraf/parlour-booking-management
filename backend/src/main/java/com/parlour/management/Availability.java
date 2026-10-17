package com.parlour.management;
import static com.parlour.management.Db.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import org.springframework.web.bind.annotation.*;
@RestController
public class Availability {
 final Db db;final Catalog catalog;Availability(Db db,Catalog catalog){this.db=db;this.catalog=catalog;}
 record Selection(List<Map<String,Object>> services,int minutes,BigDecimal total){}
 Selection selection(long pid,List<Long> ids){if(ids==null||ids.isEmpty()||ids.size()>20||new HashSet<>(ids).size()!=ids.size())throw error(400,"Select 1–20 distinct services");var selected=new ArrayList<Map<String,Object>>();int duration=0;BigDecimal total=BigDecimal.ZERO;for(long id:ids){var s=db.one("SELECT * FROM services WHERE service_id=? AND parlour_id=? AND status='ACTIVE'",id,pid);duration+=((Number)s.get("duration_min")).intValue();total=total.add(money(s.get("price")));selected.add(s);}if(duration>720)throw error(400,"Appointment exceeds 12 hours");return new Selection(selected,duration,total);}
 @GetMapping("/api/public/parlours/{pid}/availability") Map<String,Object> slots(@PathVariable long pid,@RequestParam List<Long> services,@RequestParam String date,@RequestParam(required=false)Long staff){var p=catalog.verified(pid);var s=selection(pid,services);ZoneId zone=ZoneId.of(p.get("timezone").toString());LocalDate day=LocalDate.parse(date);if(day.isBefore(LocalDate.now(zone))||day.isAfter(LocalDate.now(zone).plusDays(90)))throw error(400,"Choose a date within the next 90 days");var result=new ArrayList<Map<String,Object>>();for(var h:db.list("SELECT * FROM business_hours WHERE parlour_id=? AND day_of_week=?",pid,day.getDayOfWeek().getValue())){LocalTime open=time(h.get("start_time")),close=time(h.get("end_time"));for(LocalDateTime at=day.atTime(open);!at.plusMinutes(s.minutes()).isAfter(day.atTime(close));at=at.plusMinutes(15)){Instant start=at.atZone(zone).toInstant();Long chosen=choose(pid,services,staff,start,s.minutes(),0);if(chosen!=null)result.add(map("start_at",start,"end_at",start.plusSeconds(s.minutes()*60L),"staff_id",chosen));}}return map("slots",result,"total_amount",s.total(),"duration_min",s.minutes(),"timezone",zone.getId());}
 Long choose(long pid,List<Long> services,Long preferred,Instant start,int minutes,long exclude){var p=db.one("SELECT * FROM parlours WHERE parlour_id=?",pid);ZoneId zone=ZoneId.of(p.get("timezone").toString());var at=start.atZone(zone);Instant end=start.plusSeconds(minutes*60L);if(!start.isAfter(Instant.now())||at.toLocalDate().isAfter(LocalDate.now(zone).plusDays(90))||!end.atZone(zone).toLocalDate().equals(at.toLocalDate()))return null;
  if(db.count("SELECT COUNT(*) FROM holidays WHERE parlour_id=? AND holiday_date=?",pid,java.sql.Date.valueOf(at.toLocalDate()))>0)return null;
  if(!contains(db.list("SELECT * FROM business_hours WHERE parlour_id=? AND day_of_week=?",pid,at.getDayOfWeek().getValue()),at.toLocalTime(),end.atZone(zone).toLocalTime()))return null;
  for(var st:db.list("SELECT * FROM staff WHERE parlour_id=? AND status='ACTIVE' ORDER BY staff_id",pid)){long sid=id(st,"staff_id");if(preferred!=null&&preferred!=sid)continue;boolean qualified=true;for(long service:services)if(db.count("SELECT COUNT(*) FROM staff_services WHERE staff_id=? AND service_id=?",sid,service)!=1)qualified=false;if(!qualified)continue;
   if(!contains(db.list("SELECT * FROM staff_schedules WHERE staff_id=? AND day_of_week=? AND status='ACTIVE'",sid,at.getDayOfWeek().getValue()),at.toLocalTime(),end.atZone(zone).toLocalTime()))continue;
   boolean blocked=db.list("SELECT * FROM blocked_slots WHERE parlour_id=? AND start_at<?",pid,Timestamp.from(end)).stream().anyMatch(bl->(bl.get("staff_id")==null||id(bl,"staff_id")==sid)&&Bookings.instant(bl.get("start_at")).isBefore(end)&&Bookings.instant(bl.get("end_at")).isAfter(start));
   if(blocked)continue;
   boolean clash=db.list("SELECT * FROM bookings WHERE parlour_id=? AND staff_id=? AND end_at>?",pid,sid,Timestamp.from(start)).stream().anyMatch(r->id(r,"booking_id")!=exclude&&Bookings.instant(r.get("start_at")).isBefore(end)&&(Set.of("CONFIRMED","PENDING_OWNER","CANCEL_PENDING","COMPLETED").contains(r.get("status"))||("PENDING_PAYMENT".equals(r.get("status"))&&r.get("hold_until")!=null&&Bookings.instant(r.get("hold_until")).isAfter(Instant.now()))));
   if(!clash)return sid;
  }return null;
 }
 static LocalTime time(Object v){return v instanceof java.sql.Time t?t.toLocalTime():LocalTime.parse(v.toString());}
 static boolean contains(List<Map<String,Object>> periods,LocalTime start,LocalTime end){return periods.stream().anyMatch(p->!start.isBefore(time(p.get("start_time")))&&!end.isAfter(time(p.get("end_time"))));}
}
