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
 @Scheduled(fixedDelay=60000) void expire(){for(var b:db.list("SELECT booking_id,parlour_id FROM bookings WHERE status='PENDING_PAYMENT' AND hold_until<? LIMIT 100",Timestamp.from(Instant.now()))){tx.executeWithoutResult(s->{db.one("SELECT parlour_id FROM parlours WHERE parlour_id=? FOR UPDATE",b.get("parlour_id"));if(db.update("UPDATE bookings SET status='EXPIRED' WHERE booking_id=? AND status='PENDING_PAYMENT' AND hold_until<?",b.get("booking_id"),Timestamp.from(Instant.now()))==1)bookings.event(id(b,"booking_id"),"EXPIRED","Payment reservation expired","Choose another available slot");});}}
 @Scheduled(fixedDelay=60000) void remind(){for(var b:db.list("SELECT booking_id,parlour_id FROM bookings WHERE status='CONFIRMED' AND start_at>? AND start_at<? LIMIT 100",Timestamp.from(Instant.now()),Timestamp.from(Instant.now().plusSeconds(3600)))){tx.executeWithoutResult(s->{db.one("SELECT parlour_id FROM parlours WHERE parlour_id=? FOR UPDATE",b.get("parlour_id"));long bid=id(b,"booking_id");if(db.count("SELECT COUNT(*) FROM notifications WHERE booking_id=? AND type='REMINDER'",bid)==0)bookings.event(bid,"REMINDER","Your appointment is approaching","Your booking starts within one hour");});}}
}
