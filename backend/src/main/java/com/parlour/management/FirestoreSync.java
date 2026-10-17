package com.parlour.management;
import static com.parlour.management.Db.*;
import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.cloud.firestore.*;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import jakarta.annotation.PreDestroy;

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
 static final Map<String,Source> SOURCE_BY_NAME=SOURCES.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Source::collection,s->s));
 final Db db;final Auth auth;final FirebaseServices firebase;final Firestore store;final String project;
 final Set<String> pending=ConcurrentHashMap.newKeySet();final AtomicBoolean workerQueued=new AtomicBoolean();
 final Map<String,Integer> failures=new ConcurrentHashMap<>();final Map<String,Long> backoffUntil=new ConcurrentHashMap<>();
 static long backoffMs(int f){return Math.min(300_000L,(1L<<Math.min(f,20))*10_000L);}
 final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"firestore-sync");t.setDaemon(true);return t;});
 FirestoreSync(Db db,Auth auth,FirebaseServices firebase,@Value("${app.firebase-project}")String project){
  this.db=db;this.auth=auth;this.firebase=firebase;this.project=project;
  this.store=firebase.enabled?FirestoreClient.getFirestore(firebase.app):null;
 }
 @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT,fallbackExecution=true)
 void changed(Db.Mutation event){queue(event.table());}
 void queue(String table){if(!firebase.enabled||!SOURCE_BY_NAME.containsKey(table))return;pending.add(table);if(workerQueued.compareAndSet(false,true))worker.execute(this::drain);}
 void drain(){try{while(true){
  long now=System.currentTimeMillis();
  var due=new LinkedHashSet<String>();long soonest=Long.MAX_VALUE;
  for(String t:pending){long until=backoffUntil.getOrDefault(t,0L);if(until<=now)due.add(t);else soonest=Math.min(soonest,until);}
  pending.removeAll(due);
  if(due.isEmpty()){
   if(pending.isEmpty())break;
   try{Thread.sleep(Math.min(Math.max(soonest-now,0L),60_000L));}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
   continue;
  }
  try{
   var failed=failedCollections(syncNames(due,false));
   var ok=new LinkedHashSet<String>(due);ok.removeAll(failed);
   for(String t:ok){failures.remove(t);backoffUntil.remove(t);}
   if(!failed.isEmpty()){
    long retryAt=System.currentTimeMillis();pending.addAll(failed);
    for(String t:failed){int f=failures.merge(t,1,Integer::sum);backoffUntil.put(t,retryAt+backoffMs(f));}
    recordSyncFailure(failed,new RuntimeException("collections failed: "+failed));
   }
  }
  catch(Exception e){pending.addAll(due);for(String t:due){int f=failures.merge(t,1,Integer::sum);backoffUntil.put(t,now+backoffMs(f));}recordSyncFailure(due,e);}
 }}finally{workerQueued.set(false);if(!pending.isEmpty())queue(pending.iterator().next());}}
 static Set<String> failedCollections(Map<String,Object> result){
  var failed=new LinkedHashSet<String>();Object cs=result.get("collection_status");
  if(cs instanceof Map<?,?> m)for(var e:m.entrySet())if(e.getValue() instanceof Map<?,?> v&&"FAILED".equals(v.get("state")))failed.add(String.valueOf(e.getKey()));
  return failed;
 }
 void recordSyncFailure(Set<String> tables,Exception e){
  System.err.println("Firestore event sync failed for "+tables+": "+e.getMessage());
  try{store.collection("system").document("core").set(map("last_event_sync_failed_at",FieldValue.serverTimestamp(),"last_event_sync_failed_collections",new ArrayList<>(tables),"last_event_sync_error",String.valueOf(e.getMessage())),SetOptions.merge()).get(15,TimeUnit.SECONDS);}
  catch(Exception ex){System.err.println("Firestore failure-record write failed: "+ex.getMessage());}
 }
 synchronized void syncSelected(Set<String> names){syncNames(names,false);}
 synchronized Map<String,Object> syncAll(){return syncNames(new LinkedHashSet<>(SOURCES.stream().map(Source::collection).toList()),true);}
 synchronized Map<String,Object> syncNames(Set<String> names,boolean full){
  var status=new LinkedHashMap<String,Object>();long documents=0,failed=0;
  for(String name:names){
   var source=SOURCE_BY_NAME.get(name);if(source==null)continue;
   try{long n=sync(source);documents+=n;status.put(name,map("state","OK","documents",n,"synced_at",new java.util.Date()));}
   catch(Exception e){failed++;String msg=e.getMessage()!=null?e.getMessage():e.toString();status.put(name,map("state","FAILED","error",msg,"failed_at",new java.util.Date()));System.err.println("Firestore sync failed for "+name+": "+msg);}
  }
  try{
   var meta=new LinkedHashMap<String,Object>();
   meta.put("schema_version",1);meta.put("project_id",project);meta.put("java_package","com.parlour.management");
   meta.put("source_of_truth","mysql");meta.put("client_writes",false);meta.put("sync_mode","after_commit_with_periodic_reconciliation");
   meta.put("collections",SOURCES.stream().map(Source::collection).toList());
   meta.put("collection_status",status);
   if(full)meta.put("last_full_sync_at",FieldValue.serverTimestamp());
   else{meta.put("last_event_sync_at",FieldValue.serverTimestamp());meta.put("last_event_collections",new ArrayList<>(names));meta.put("last_event_documents",documents);}
   store.collection("system").document("core").set(meta,SetOptions.merge()).get(15,TimeUnit.SECONDS);
  }catch(Exception e){System.err.println("Firestore sync metadata write failed: "+e.getMessage());}
  return map("ok",failed==0,"collections",names.size(),"documents",documents,"failed",failed,"collection_status",status,"project",project);
 }
 @PreDestroy void shutdown(){worker.shutdown();}
 @Scheduled(fixedDelayString="${app.firestore-sync-ms:300000}",initialDelayString="${app.firestore-sync-initial-delay-ms:15000}")
 void scheduled(){if(!firebase.enabled)return;try{syncAll();}catch(Exception e){System.err.println("Firestore projection failed: "+e.getMessage());}}
 @PostMapping("/api/admin/firestore/sync") Map<String,Object> manual(){
  auth.require("ADMIN");if(!firebase.enabled)throw error(503,"Firebase is not configured");
  return syncAll();
 }
 @PostMapping("/api/admin/firestore/sync/retry") Map<String,Object> retry(){
  auth.require("ADMIN");if(!firebase.enabled)throw error(503,"Firebase is not configured");
  try{
   var snap=store.collection("system").document("core").get().get(10,TimeUnit.SECONDS);
   var failed=new LinkedHashSet<String>();
   if(snap.exists()&&snap.get("collection_status") instanceof Map<?,?> m)for(var e:m.entrySet())
    if(e.getValue() instanceof Map<?,?> v&&"FAILED".equals(v.get("state")))failed.add(String.valueOf(e.getKey()));
   if(failed.isEmpty())return map("ok",true,"retried",0);
   return syncNames(failed,false);
  }catch(Exception e){throw error(503,"Firestore sync retry failed");}
 }
 @GetMapping("/api/admin/firestore/status") Map<String,Object> status(){
  auth.require("ADMIN");if(!firebase.enabled)return map("enabled",false,"project",project);
  try{var snap=store.collection("system").document("core").get().get(10,TimeUnit.SECONDS);return map("enabled",true,"project",project,"initialized",snap.exists(),"metadata",snap.exists()?normalizeMeta(snap.getData()):Map.of());}
  catch(Exception e){throw error(503,"Firestore is unreachable");}
 }
 static Object normalizeMeta(Object v){
  if(v instanceof com.google.cloud.Timestamp t)return new java.util.Date(t.getSeconds()*1000+t.getNanos()/1_000_000);
  if(v instanceof Map<?,?> m){var o=new LinkedHashMap<String,Object>();for(var e:m.entrySet())o.put(String.valueOf(e.getKey()),normalizeMeta(e.getValue()));return o;}
  if(v instanceof List<?> l){var o=new ArrayList<>();for(var i:l)o.add(normalizeMeta(i));return o;}
  return v;
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
