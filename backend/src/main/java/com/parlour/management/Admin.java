package com.parlour.management;
import static com.parlour.management.Db.*;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
public class Admin {
 final Db db;final Auth auth;final Bookings bookings;
 Admin(Db db,Auth auth,Bookings bookings){this.db=db;this.auth=auth;this.bookings=bookings;}
 @GetMapping("/api/admin/dashboard") Map<String,Object> dashboard(){auth.require("ADMIN");java.math.BigDecimal paid=java.math.BigDecimal.ZERO;for(var r:db.list("SELECT amount FROM payments WHERE status='PAID'"))paid=paid.add(money(r.get("amount")));return map("parlours",db.count("SELECT COUNT(*) FROM parlours"),"pending_verification",db.count("SELECT COUNT(*) FROM parlours WHERE status='PENDING'"),"bookings",db.count("SELECT COUNT(*) FROM bookings"),"open_complaints",db.count("SELECT COUNT(*) FROM complaints WHERE status='OPEN'"),"paid_amount",paid);}
 @GetMapping("/api/admin/{table}") List<Map<String,Object>> list(@PathVariable String table){auth.require("ADMIN");if(!Set.of("parlours","users","categories","banners","complaints","payments","bookings","admin_actions","refund_requests").contains(table))throw error(404,"Unknown resource");return db.list(table.equals("users")?"SELECT user_id,name,email,phone,role,status,created_at FROM users LIMIT 300":"SELECT * FROM "+table+" LIMIT 300");}
 @PostMapping("/api/admin/parlours/{pid}/verify") Map<String,Object> verify(@PathVariable long pid,@RequestBody Map<String,Object>b){long admin=auth.require("ADMIN");String status=text(b,"status",20);if(!Set.of("VERIFIED","SUSPENDED","REJECTED").contains(status))throw error(400,"Invalid verification status");String reason=text(b,"reason",1000);db.tx(()->{db.one("SELECT * FROM parlours WHERE parlour_id=?",pid);db.update("UPDATE parlours SET status=? WHERE parlour_id=?",status,pid);audit(admin,"parlours",pid,status,reason);});return map("ok",true);}
 @PostMapping("/api/admin/users/{uid}/status") Map<String,Object> user(@PathVariable long uid,@RequestBody Map<String,Object>b){long admin=auth.require("ADMIN");var u=db.one("SELECT * FROM users WHERE user_id=?",uid);if("ADMIN".equals(u.get("role")))throw error(400,"Administrator accounts cannot be suspended here");String status=text(b,"status",16);if(!Set.of("ACTIVE","SUSPENDED").contains(status))throw error(400,"Invalid status");String reason=text(b,"reason",1000);db.tx(()->{db.update("UPDATE users SET status=? WHERE user_id=?",status,uid);if(status.equals("SUSPENDED"))db.update("UPDATE parlours SET status='SUSPENDED' WHERE owner_id=?",uid);audit(admin,"users",uid,status,reason);});return map("ok",true);}
 @PostMapping("/api/admin/{table}") Map<String,Object> add(@PathVariable String table,@RequestBody Map<String,Object>b){long uid=auth.require("ADMIN");Map<String,Object>f;if(table.equals("categories"))f=map("name",text(b,"name",50));else if(table.equals("banners")){String url=text(b,"image_url",2048);if(!url.startsWith("https://"))throw error(400,"HTTPS image required");f=map("title",text(b,"title",160),"image_url",url);}else throw error(404,"Unknown resource");return db.tx(()->{long nid=db.insert(table,f);audit(uid,table,nid,"CREATED","Created through administration");return map("id",nid);});}
 @PostMapping("/api/complaints") Map<String,Object> complaint(@RequestBody Map<String,Object>b){long uid=id(auth.current(),"user_id"),bid=Long.parseLong(b.get("booking_id").toString());if(id(bookings.accessible(bid),"customer_id")!=uid)throw error(403,"Not your booking");return map("complaint_id",db.insert("complaints",map("user_id",uid,"booking_id",bid,"body",text(b,"body",2000))));}
 @PostMapping("/api/admin/complaints/{id}/resolve") Map<String,Object> resolve(@PathVariable long id,@RequestBody Map<String,Object>b){long uid=auth.require("ADMIN");String resolution=text(b,"resolution",2000);db.tx(()->{db.one("SELECT * FROM complaints WHERE complaint_id=?",id);db.update("UPDATE complaints SET status='RESOLVED',resolution=? WHERE complaint_id=?",resolution,id);audit(uid,"complaints",id,"RESOLVED",resolution.substring(0,Math.min(resolution.length(),1000)));});return map("ok",true);}
 @PostMapping("/api/admin/bookings/{bid}/status") Map<String,Object> bookingStatus(@PathVariable long bid,@RequestBody Map<String,Object>b){long admin=auth.require("ADMIN");String next=text(b,"status",24);Object rv=b.get("reason");
  db.tx(()->{
   var bk=db.one("SELECT * FROM bookings WHERE booking_id=?",bid);String old=bk.get("status").toString();
   if(next.equals(old))return;
   long[] pt=bookings.parties(bk);
   if(old.equals("PENDING_OWNER")&&Set.of("CONFIRMED","REJECTED").contains(next)){if("REJECTED".equals(next))bookings.queueRefund(bid,bookings.paid(bid),"Administrator rejected appointment");db.update("UPDATE bookings SET status=? WHERE booking_id=?",next,bid);}
   else if(old.equals("CONFIRMED")&&"COMPLETED".equals(next)&&!Bookings.instant(bk.get("end_at")).isAfter(Instant.now()))db.update("UPDATE bookings SET status=? WHERE booking_id=?",next,bid);
   else if(old.equals("CANCEL_PENDING")&&"CANCELLED".equals(next))bookings.cancelNow(bid,money(bk.get("cancel_refund_quote")),pt);
   else throw error(409,"Invalid booking transition");
   bookings.notifyParties(bid,pt[0],pt[1],next,"Booking updated by administrator",old+" -> "+next);
   audit(admin,"bookings",bid,old+" -> "+next,rv instanceof String s?s.trim():"");});
  return map("ok",true);}
 void audit(long uid,String type,long id,String action,String reason){db.insert("admin_actions",map("admin_id",uid,"target_type",type,"target_id",id,"action",action,"reason",reason));}
}
