package com.parlour.management;
import java.util.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/**
 * TEMPORARY: one-time H2 -> Firestore migration. Run with --migrate.h2=true
 * (and DB_URL pointing at the legacy H2 file plus Firestore credentials).
 * Preserves every numeric id. Deleted after the cutover.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name="migrate.h2",havingValue="true")
public class MigrationRunner implements ApplicationRunner {
 final Db db;
 MigrationRunner(Db db){this.db=db;}
 static final List<String> TABLES=List.of("users","parlours","parlour_images","services","staff","staff_services","staff_schedules","holidays","blocked_slots","cancellation_policies","bookings","booking_items","payments","ratings","notifications","device_tokens","admin_actions","business_hours","complaints","categories","banners","refund_requests");
 public void run(ApplicationArguments args){
  var ds=new DriverManagerDataSource(System.getenv("DB_URL"),System.getenv("DB_USER"),System.getenv().getOrDefault("DB_PASSWORD",""));
  ds.setDriverClassName("org.h2.Driver");
  var legacy=new LegacyDb(new JdbcTemplate(ds),null);
  for(String t:TABLES){
   var rows=legacy.list("SELECT * FROM "+t);
   int n=0;
   for(var r:rows){
    if(t.equals("device_tokens")){db.update("INSERT INTO device_tokens(token,user_id) VALUES (?,?)",r.get("token"),r.get("user_id"));n++;continue;}
    if(t.equals("staff_services")){db.update("INSERT INTO staff_services(staff_id,service_id,expertise_level) VALUES (?,?,?)",r.get("staff_id"),r.get("service_id"),r.get("expertise_level"));n++;continue;}
    String pk=Db.primary(t);
    long id=((Number)r.get(pk)).longValue();
    var f=new LinkedHashMap<String,Object>(r);f.remove(pk);
    // H2 returns java.sql.* / BigDecimal types; Db.insertWithId normalizes them.
    db.insertWithId(t,id,f);n++;
   }
   System.out.println("MIGRATED "+t+": "+n+" rows");
  }
  System.out.println("MIGRATION COMPLETE");
 }
}
