package com.parlour.management;
import static com.parlour.management.Db.*;
import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.google.cloud.firestore.*;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

@RestController
public class FirestoreSync {
 record Source(String collection,List<String> keys){}
 static final List<Source> SOURCES=List.of(
  new Source("users",List.of("user_id")),
  new Source("parlours",List.of("parlour_id")),
  new Source("parlour_images",List.of("image_id")),
  new Source("services",List.of("service_id")),
  new Source("staff",List.of("staff_id")),
  new Source("staff_services",List.of("staff_id","service_id")),
  new Source("staff_schedules",List.of("schedule_id")),
  new Source("holidays",List.of("holiday_id")),
  new Source("blocked_slots",List.of("block_id")),
  new Source("cancellation_policies",List.of("policy_id")),
  new Source("bookings",List.of("booking_id")),
  new Source("booking_items",List.of("booking_item_id")),
  new Source("payments",List.of("payment_id")),
  new Source("ratings",List.of("rating_id")),
  new Source("notifications",List.of("notification_id")),
  new Source("admin_actions",List.of("action_id")),
  new Source("business_hours",List.of("hours_id")),
  new Source("complaints",List.of("complaint_id")),
  new Source("categories",List.of("category_id")),
  new Source("banners",List.of("banner_id")),
  new Source("refund_requests",List.of("refund_id"))
 );
 final Db db;final Auth auth;final FirebaseServices firebase;final Firestore store;final String project;
 FirestoreSync(Db db,Auth auth,FirebaseServices firebase,@Value("${app.firebase-project}")String project){
  this.db=db;this.auth=auth;this.firebase=firebase;this.project=project;
  this.store=firebase.enabled?FirestoreClient.getFirestore(firebase.app):null;
 }
 @Scheduled(fixedDelayString="${app.firestore-sync-ms:300000}",initialDelayString="${app.firestore-sync-initial-delay-ms:15000}")
 void scheduled(){if(!firebase.enabled)return;try{syncAll();}catch(Exception e){System.err.println("Firestore projection failed: "+e.getMessage());}}
 @PostMapping("/api/admin/firestore/sync") Map<String,Object> manual(){
  auth.require("ADMIN");if(!firebase.enabled)throw error(503,"Firebase is not configured");
  try{return syncAll();}catch(Exception e){throw error(503,"Firestore sync failed");}
 }
 @GetMapping("/api/admin/firestore/status") Map<String,Object> status(){
  auth.require("ADMIN");if(!firebase.enabled)return map("enabled",false,"project",project);
  try{var snap=store.collection("system").document("core").get().get(10,TimeUnit.SECONDS);return map("enabled",true,"project",project,"initialized",snap.exists(),"metadata",snap.exists()?snap.getData():Map.of());}
  catch(Exception e){throw error(503,"Firestore is unreachable");}
 }
 synchronized Map<String,Object> syncAll() throws Exception{
  long documents=0;
  for(var source:SOURCES)documents+=sync(source);
  store.collection("system").document("core").set(map(
   "schema_version",1,"project_id",project,"java_package","com.parlour.management",
   "source_of_truth","mysql","client_writes",false,
   "collections",SOURCES.stream().map(Source::collection).toList(),
   "last_sync_at",FieldValue.serverTimestamp()
  )).get(15,TimeUnit.SECONDS);
  return map("ok",true,"collections",SOURCES.size(),"documents",documents,"project",project);
 }
 long sync(Source source) throws Exception{
  var rows=db.list("SELECT * FROM "+source.collection());
  var expected=new HashSet<String>();
  WriteBatch batch=store.batch();int ops=0;
  for(var row:rows){
   String id=docId(row,source.keys());expected.add(id);
   batch.set(store.collection(source.collection()).document(id),normalizeRow(row));ops++;
   if(ops>=400){batch.commit().get(30,TimeUnit.SECONDS);batch=store.batch();ops=0;}
  }
  var current=store.collection(source.collection()).get().get(30,TimeUnit.SECONDS);
  for(var doc:current.getDocuments())if(!expected.contains(doc.getId())){
   batch.delete(doc.getReference());ops++;
   if(ops>=400){batch.commit().get(30,TimeUnit.SECONDS);batch=store.batch();ops=0;}
  }
  if(ops>0)batch.commit().get(30,TimeUnit.SECONDS);
  return rows.size();
 }
 static String docId(Map<String,Object> row,List<String> keys){
  var parts=new ArrayList<String>();for(String key:keys)parts.add(String.valueOf(row.get(key)));return String.join("_",parts);
 }
 static Map<String,Object> normalizeRow(Map<String,Object> row){
  var out=new LinkedHashMap<String,Object>();
  for(var e:row.entrySet()){String key=e.getKey();if(Set.of("password_hash","request_hash","idempotency_key").contains(key))continue;out.put(key,normalize(e.getValue()));}
  return out;
 }
 static Object normalize(Object value){
  if(value==null)return null;
  if(value instanceof BigDecimal n)return n.doubleValue();
  if(value instanceof Timestamp t)return new java.util.Date(t.getTime());
  if(value instanceof java.sql.Date d)return d.toLocalDate().toString();
  if(value instanceof Time t)return t.toLocalTime().toString();
  if(value instanceof Number||value instanceof Boolean||value instanceof String||value instanceof java.util.Date)return value;
  return value.toString();
 }
}
