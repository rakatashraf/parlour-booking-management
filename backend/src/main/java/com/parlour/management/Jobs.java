package com.parlour.management;
import static com.parlour.management.Db.*;
import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Component
public class Jobs {
 final Db db;final Bookings bookings;final TransactionTemplate tx;
 Jobs(Db db,Bookings bookings,PlatformTransactionManager manager){this.db=db;this.bookings=bookings;tx=new TransactionTemplate(manager);}
 @Scheduled(fixedDelay=60000) void expire(){for(var b:db.list("SELECT booking_id,parlour_id FROM bookings WHERE status='PENDING_PAYMENT' AND hold_until<? ORDER BY booking_id ASC LIMIT 100",Timestamp.from(Instant.now()))){tx.executeWithoutResult(s->{db.one("SELECT parlour_id FROM parlours WHERE parlour_id=? FOR UPDATE",b.get("parlour_id"));if(db.update("UPDATE bookings SET status='EXPIRED' WHERE booking_id=? AND status='PENDING_PAYMENT' AND hold_until<?",b.get("booking_id"),Timestamp.from(Instant.now()))==1)bookings.event(id(b,"booking_id"),"EXPIRED","Payment reservation expired","Choose another available slot");});}}
 @Scheduled(fixedDelay=60000) void remind(){for(var b:db.list("SELECT booking_id,parlour_id FROM bookings WHERE status='CONFIRMED' AND start_at>? AND start_at<? ORDER BY booking_id ASC LIMIT 100",Timestamp.from(Instant.now()),Timestamp.from(Instant.now().plusSeconds(3600)))){tx.executeWithoutResult(s->{db.one("SELECT parlour_id FROM parlours WHERE parlour_id=? FOR UPDATE",b.get("parlour_id"));long bid=id(b,"booking_id");if(db.count("SELECT COUNT(*) FROM notifications WHERE booking_id=? AND type='REMINDER'",bid)==0)bookings.event(bid,"REMINDER","Your appointment is approaching","Your booking starts within one hour");});}}
 /** Abandoned checkout rows: a PENDING payment whose booking hold has lapsed (15 min + 5 min grace) can never complete. */
 @Scheduled(fixedDelay=60000) void expirePayments(){var cutoff=Timestamp.from(Instant.now().minusSeconds(300));for(var p:db.list("SELECT p.payment_id FROM payments p JOIN bookings b ON b.booking_id=p.booking_id WHERE p.status='PENDING' AND b.hold_until<? ORDER BY p.payment_id ASC LIMIT 100",cutoff)){tx.executeWithoutResult(s->{db.update("UPDATE payments SET status='EXPIRED' WHERE payment_id=? AND status='PENDING'",p.get("payment_id"));});}}
}
