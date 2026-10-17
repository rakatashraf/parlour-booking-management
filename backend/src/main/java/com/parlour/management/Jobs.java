package com.parlour.management;
import static com.parlour.management.Db.*;
import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
@Component
public class Jobs {
 final Db db;final Bookings bookings;
 Jobs(Db db,Bookings bookings){this.db=db;this.bookings=bookings;}
 @Scheduled(fixedDelay=60000) void expire(){for(var b:db.list("SELECT booking_id,parlour_id FROM bookings WHERE status='PENDING_PAYMENT' AND hold_until<? ORDER BY booking_id ASC LIMIT 100",Timestamp.from(Instant.now()))){long bid=id(b,"booking_id");db.tx(()->{var full=db.one("SELECT * FROM bookings WHERE booking_id=?",bid);var pt=bookings.parties(full);if(db.update("UPDATE bookings SET status='EXPIRED' WHERE booking_id=? AND status='PENDING_PAYMENT' AND hold_until<?",bid,Timestamp.from(Instant.now()))==1)bookings.notifyParties(bid,pt[0],pt[1],"EXPIRED","Payment reservation expired","Choose another available slot");});}}
 @Scheduled(fixedDelay=60000) void remind(){for(var b:db.list("SELECT booking_id,parlour_id FROM bookings WHERE status='CONFIRMED' AND start_at>? AND start_at<? ORDER BY booking_id ASC LIMIT 100",Timestamp.from(Instant.now()),Timestamp.from(Instant.now().plusSeconds(3600)))){long bid=id(b,"booking_id");db.tx(()->{var full=db.one("SELECT * FROM bookings WHERE booking_id=?",bid);var pt=bookings.parties(full);if(db.count("SELECT COUNT(*) FROM notifications WHERE booking_id=? AND type='REMINDER'",bid)==0)bookings.notifyParties(bid,pt[0],pt[1],"REMINDER","Your appointment is approaching","Your booking starts within one hour");});}}
 /** Abandoned checkout rows: a PENDING payment whose booking hold has lapsed (15 min + 5 min grace) can never complete. */
 @Scheduled(fixedDelay=60000) void expirePayments(){var cutoff=Timestamp.from(Instant.now().minusSeconds(300));for(var p:db.list("SELECT payment_id,booking_id FROM payments WHERE status='PENDING' ORDER BY payment_id ASC LIMIT 100")){long payId=id(p,"payment_id");var b=db.one("SELECT hold_until FROM bookings WHERE booking_id=?",p.get("booking_id"));if(b.get("hold_until")==null||!Bookings.instant(b.get("hold_until")).isBefore(cutoff.toInstant()))continue;db.tx(()->{db.update("UPDATE payments SET status='EXPIRED' WHERE payment_id=? AND status='PENDING'",payId);});}}
}
