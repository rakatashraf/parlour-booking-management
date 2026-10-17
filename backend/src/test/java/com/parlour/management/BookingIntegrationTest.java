package com.parlour.management;
import static com.parlour.management.Db.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.jwt-secret=test-secret-with-more-than-thirty-two-bytes"})
// Runs against the Firestore emulator: set FIRESTORE_EMULATOR_HOST=localhost:8080 before running.
// Firestore is the only database; there is no H2/MySQL fallback.
class BookingIntegrationTest {
 @Autowired Db db;@Autowired TestRestTemplate http;@Autowired Auth auth;
 long pid,sid,service;String customer,other,owner;Instant start;
 ResponseEntity<Map> request(String method,String path,String token,Object body,String key){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(token!=null)h.setBearerAuth(token);if(key!=null)h.set("Idempotency-Key",key);return http.exchange(path,HttpMethod.valueOf(method),new HttpEntity<>(body,h),Map.class);}
 @BeforeEach void fixture(){String tag=UUID.randomUUID().toString();long o=db.insert("users",map("name","Owner","email",tag+"@test.invalid","role","OWNER"));owner=auth.token(o).get("token").toString();long c=db.insert("users",map("name","Guest","role","CUSTOMER"));customer=auth.token(c).get("token").toString();other=auth.token(db.insert("users",map("name","Other","role","CUSTOMER"))).get("token").toString();pid=db.insert("parlours",map("owner_id",o,"name","Test parlour","category","UNISEX","address","Test","lat",23.8,"lng",90.4,"phone","01700000000","status","VERIFIED"));service=db.insert("services",map("parlour_id",pid,"name","Cut","duration_min",60,"price",500));sid=db.insert("staff",map("parlour_id",pid,"name","Staff"));db.update("INSERT INTO staff_services VALUES (?,?,?)",sid,service,"QUALIFIED");LocalDate day=LocalDate.now(ZoneId.of("Asia/Dhaka")).plusDays(2);start=day.atTime(10,0).atZone(ZoneId.of("Asia/Dhaka")).toInstant();db.insert("business_hours",map("parlour_id",pid,"day_of_week",day.getDayOfWeek().getValue(),"start_time",java.sql.Time.valueOf("09:00:00"),"end_time",java.sql.Time.valueOf("18:00:00")));db.insert("staff_schedules",map("staff_id",sid,"day_of_week",day.getDayOfWeek().getValue(),"start_time",java.sql.Time.valueOf("09:00:00"),"end_time",java.sql.Time.valueOf("18:00:00")));db.insert("cancellation_policies",map("parlour_id",pid));}
 Map<String,Object> body(Instant at){return map("parlour_id",pid,"services",List.of(service),"staff_id",sid,"start_at",at.toString(),"customer_name","Customer","customer_phone","01711111111","payment_method","AT_PARLOUR","total_amount",1);}
 long book(){var r=request("POST","/api/bookings",customer,body(start),UUID.randomUUID().toString());assertEquals(200,r.getStatusCode().value(),r.toString());return ((Number)r.getBody().get("booking_id")).longValue();}
 @Test void priceIsAuthoritativeAndIdempotencyIsBoundToPayload(){String key=UUID.randomUUID().toString();var a=request("POST","/api/bookings",customer,body(start),key);assertEquals(200,a.getStatusCode().value());assertEquals(500.0,((Number)a.getBody().get("total_amount")).doubleValue());var b=request("POST","/api/bookings",customer,body(start),key);assertEquals(a.getBody().get("booking_id"),b.getBody().get("booking_id"));assertEquals(409,request("POST","/api/bookings",customer,body(start.plusSeconds(3600)),key).getStatusCode().value());}
 @Test void concurrentReservationsHaveOneWinner()throws Exception{var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var gate=new CountDownLatch(1);Callable<Integer> attempt=()->{ready.countDown();gate.await();return request("POST","/api/bookings",customer,body(start),UUID.randomUUID().toString()).getStatusCode().value();};try{var a=pool.submit(attempt);var b=pool.submit(attempt);ready.await();gate.countDown();var codes=new ArrayList<>(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));Collections.sort(codes);assertEquals(List.of(200,409),codes);}finally{pool.shutdownNow();}}
 @Test void cancellationReleasesSlotAndForeignGuestCannotRead(){long bid=book();assertEquals(403,request("GET","/api/bookings/"+bid,other,null,null).getStatusCode().value());assertEquals(200,request("POST","/api/bookings/"+bid+"/cancel",customer,map(),null).getStatusCode().value());assertEquals(200,request("POST","/api/bookings",other,body(start),UUID.randomUUID().toString()).getStatusCode().value());}
 @Test void holidayAndBlockedPeriodsRejectBookings(){db.insert("holidays",map("parlour_id",pid,"holiday_date",java.sql.Date.valueOf(start.atZone(ZoneId.of("Asia/Dhaka")).toLocalDate())));assertEquals(409,request("POST","/api/bookings",customer,body(start),"holiday").getStatusCode().value());db.update("DELETE FROM holidays WHERE parlour_id=?",pid);db.insert("blocked_slots",map("parlour_id",pid,"start_at",Timestamp.from(start.minusSeconds(900)),"end_at",Timestamp.from(start.plusSeconds(900))));assertEquals(409,request("POST","/api/bookings",customer,body(start),"blocked").getStatusCode().value());}
 @Test void unauthorizedOwnerCannotModifyAnotherParlour(){long o=db.insert("users",map("name","Other owner","role","OWNER"));String token=auth.token(o).get("token").toString();assertEquals(403,request("POST","/api/owner/parlours/"+pid+"/services",token,map("name","Bad","duration_min",30,"price",5),null).getStatusCode().value());}
 @Test void reviewRequiresCompletedBooking(){long bid=book();assertEquals(403,request("POST","/api/ratings",customer,map("booking_id",bid,"target_type","PARLOUR","score",5,"review","Nice"),null).getStatusCode().value());assertEquals(409,request("POST","/api/owner/bookings/"+bid+"/status",owner,map("status","COMPLETED"),null).getStatusCode().value());}

 @Test void registrationDefaultsToCustomerAndRejectsAdminEscalation(){
  String tag=UUID.randomUUID().toString();
  var created=request("POST","/api/auth/register",null,map("name","New Customer","email",tag+"@test.invalid","phone","01722222222","password","test-password-123"),null);
  assertEquals(200,created.getStatusCode().value(),created.toString());
  assertEquals("CUSTOMER",((Map)created.getBody().get("user")).get("role"));
  var escalation=request("POST","/api/auth/register",null,map("name","Bad Admin","email",UUID.randomUUID()+"@test.invalid","phone","01733333333","password","test-password-123","role","ADMIN"),null);
  assertEquals(400,escalation.getStatusCode().value(),escalation.toString());
 }
 @Test void customerCanBrowseVerifiedParlourAndOwnerCanManageOwnServices(){
  var search=request("GET","/api/public/parlours",null,null,null);
  assertEquals(200,search.getStatusCode().value());
  assertTrue(((List<Map<String,Object>>)search.getBody()).stream().anyMatch(p->((Number)p.get("parlour_id")).longValue()==pid));
  var profile=request("GET","/api/public/parlours/"+pid,null,null,null);
  assertEquals(200,profile.getStatusCode().value());
  var created=request("POST","/api/owner/parlours/"+pid+"/services",owner,map("name","Added service","description","Integration test","duration_min",30,"price",250,"status","ACTIVE"),null);
  assertEquals(200,created.getStatusCode().value(),created.toString());
  var forbidden=request("GET","/api/owner/parlours/"+pid+"/services",customer,null,null);
  assertEquals(403,forbidden.getStatusCode().value());
 }
 @Test void adminResourcesRequireAdminRole(){
  long adminId=db.insert("users",map("name","Test admin","role","ADMIN","status","ACTIVE"));
  String admin=auth.token(adminId).get("token").toString();
  assertEquals(403,request("GET","/api/admin/users",customer,null,null).getStatusCode().value());
  assertEquals(403,request("GET","/api/admin/users",owner,null,null).getStatusCode().value());
  assertEquals(200,request("GET","/api/admin/users",admin,null,null).getStatusCode().value());
 }
}
