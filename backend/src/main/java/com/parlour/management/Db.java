package com.parlour.management;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.regex.*;

/**
 * Firestore-backed data access. Firestore is the ONLY database.
 *
 * Keeps the same method signatures the codebase already uses
 * (list/one/count/update/insert/patch) and translates a deliberately small
 * SQL dialect to Firestore:
 *   SELECT <cols> FROM <table> [WHERE <c> [AND <c>...]] [ORDER BY <col> [ASC|DESC]...] [LIMIT n]
 *   UPDATE <table> SET <a>,... WHERE <c> [AND ...]
 *   INSERT INTO <table> [(cols)] VALUES (?,?,...)
 *   DELETE FROM <table> WHERE <c> [AND ...]
 * where each <c> is one of: col = ?, col = 'lit', col = 123, col <>/?, col </<=/>/>= ?,
 * col IS NULL, col IS NOT NULL, col IN (...). No JOINs, subselects or OR groups:
 * those call sites compose simple queries in Java instead, and anything outside
 * the dialect fails loudly (never silently wrong).
 *
 * Query planning: equality predicates go to Firestore; inequalities on a single
 * field go to Firestore; everything else (extra inequalities, IN, IS NULL,
 * LIKE-free text matching) is filtered in Java. ORDER BY/LIMIT are applied in
 * Java so no composite indexes are ever required.
 *
 * Type mapping: java.sql.Timestamp <-> Firestore Timestamp (read back as
 * java.sql.Timestamp so callers and JSON output are unchanged),
 * java.sql.Date <-> ISO "yyyy-MM-dd" string, java.sql.Time <-> "HH:MM:SS"
 * string, BigDecimal <-> Double, Integer -> Long. Document IDs are the numeric
 * primary keys as strings, so every long id in the API and the Android app is
 * unchanged. Auto-increment comes from a _counters collection.
 */
@Repository
public class Db {
 final Firestore firestore;
 Db(Firestore firestore){this.firestore=firestore;}

 /** Current Firestore transaction for this thread, if inside tx(). */
 final ThreadLocal<Transaction> current=new ThreadLocal<>();

 @FunctionalInterface public interface TxWork<T>{T run() throws Exception;}

 /** Run work in a Firestore transaction. Reads must precede writes inside. */
 // Pre-allocated ID cache for transactions: all counter reads happen before any
 // writes, satisfying Firestore's "reads before writes" rule.
 static final List<String> ID_TABLES=List.of("users","parlours","parlour_images","services","staff","staff_schedules","holidays","blocked_slots","bookings","booking_items","payments","ratings","cancellation_policies","notifications","admin_actions","business_hours","complaints","categories","banners","refund_requests");
 final ThreadLocal<Map<String,Long>> idCache=new ThreadLocal<>();
 final ThreadLocal<java.util.Set<String>> idUsed=new ThreadLocal<>();

 public <T> T tx(TxWork<T> work){
  if(current.get()!=null){try{return work.run();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}}
  SecurityContext ctx=SecurityContextHolder.getContext(); // propagate auth to tx thread
  try{
   return firestore.runTransaction(t->{
    SecurityContextHolder.setContext(ctx);
    current.set(t);
    try{
     // Pre-read all ID counters before any writes are allowed.
     var cache=new java.util.HashMap<String,Long>();
     for(String table:ID_TABLES){
      var snap=t.get(firestore.collection("_counters").document(table)).get();
      long next=snap.exists()&&snap.getLong("next")!=null?snap.getLong("next"):1L;
      cache.put(table,next);
     }
     idCache.set(cache);idUsed.set(new java.util.HashSet<>());
     T result=work.run();
     // Write back counters for tables that actually allocated IDs.
     for(String table:idUsed.get()){
      t.set(firestore.collection("_counters").document(table),Map.of("next",cache.get(table)));
     }
     return result;
    }finally{current.remove();idCache.remove();idUsed.remove();SecurityContextHolder.clearContext();}
   }).get();
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
  catch(ExecutionException e){throw unwrap(e);}
 }
 public void tx(Runnable work){tx(()->{work.run();return null;});}

 // ------------------------------------------------------------------ reads

 List<Map<String,Object>> list(String sql,Object... args){return select(sql,args,false).rows();}

 Map<String,Object> one(String sql,Object... args){var rows=list(sql,args);if(rows.isEmpty())throw error(404,"Record not found");return rows.get(0);}

 long count(String sql,Object... args){
  var q=parseSelect(sql);
  if(!q.isCount)throw new IllegalArgumentException("count() needs SELECT COUNT(*): "+sql);
  Transaction t=current.get();
  if(t==null&&allFirestoreSafe(q.where(),args)){ // pure Firestore aggregation, no doc reads (not allowed inside transactions)
   try{
    Query fq=buildQuery(q.where(),q.table(),args);
    long n=fq.count().get().get().getCount();
    return n;
   }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
   catch(ExecutionException e){throw unwrap(e);}
  }
  var rows=select(sql,args,false).rows(); // select() already computes the count for isCount queries
  return ((Number)rows.get(0).get("count")).longValue();
 }

 // ----------------------------------------------------------------- writes

 int update(String sql,Object... args){
  String s=sql.trim();
  if(s.regionMatches(true,0,"INSERT",0,6))return doInsert(s,args);
  if(s.regionMatches(true,0,"DELETE",0,6))return doDelete(s,args);
  var m=Pattern.compile("^\\s*UPDATE\\s+(\\w+)\\s+SET\\s+(.+?)\\s+WHERE\\s+(.+?)\\s*$",Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(s);
  if(!m.matches())throw new IllegalArgumentException("Unsupported UPDATE: "+sql);
  String table=m.group(1);var assigns=parseAssigns(m.group(2));
  int setArgs=0;for(var a:assigns)if(a.op().equals("set"))setArgs++;
  var where=parseWhere(m.group(3),setArgs);
  // Fast path: single PK equality needs no read (avoids tx read-after-write).
  String pk=null;try{pk=primary(table);}catch(IllegalArgumentException e){/* no PK */}
  if(pk!=null&&where.size()==1&&where.get(0).op().equals("=")&&where.get(0).col().equals(pk)&&where.get(0).argIx()>=0){
   Object pkVal=args[where.get(0).argIx()];
   if(pkVal!=null){
    var data=new LinkedHashMap<String,Object>();
    for(var a:assigns){
     if(a.op().equals("set"))data.put(a.col(),toStored(normArg(args[a.argIx()])));
     else if(a.op().equals("lit"))data.put(a.col(),toStored(parseLiteral(a.literal())));
     else if(a.op().equals("incr"))data.put(a.col(),com.google.cloud.firestore.FieldValue.increment(a.delta()));
    }
    writeDoc(firestore.collection(table).document(String.valueOf(((Number)normArg(pkVal)).longValue())),data,true);
    return 1;
   }
  }
  var matches=findMatches(table,where,args);
  int n=0;
  for(var hit:matches){
   var data=new LinkedHashMap<String,Object>();
   for(var a:assigns){
    if(a.op().equals("set"))data.put(a.col(),toStored(normArg(args[a.argIx()])));
    else if(a.op().equals("lit"))data.put(a.col(),toStored(parseLiteral(a.literal())));
    else if(a.op().equals("incr")){
     Object cur=hit.row().get(a.col());
     long v=cur instanceof Number num?num.longValue():0L;
     data.put(a.col(),v+a.delta());
    }
   }
   writeDoc(hit.ref(),data,true);n++;
  }
  return n;
 }

 // Column defaults carried over from the legacy SQL schema (V1), applied when
 // the caller does not supply the field. Firestore has no DDL defaults.
 static final Map<String,Map<String,Object>> DEFAULTS=Map.ofEntries(
  Map.entry("users",Map.of("status","ACTIVE")),
  Map.entry("parlours",Map.of("rating",0,"status","PENDING","timezone","Asia/Dhaka")),
  Map.entry("parlour_images",Map.of("sort_order",0)),
  Map.entry("services",Map.of("status","ACTIVE")),
  Map.entry("staff",Map.of("experience",0,"rating",0,"status","ACTIVE")),
  Map.entry("staff_schedules",Map.of("status","ACTIVE")),
  Map.entry("cancellation_policies",Map.of("advance_required",false,"min_advance_pct",0,"cancel_window_hr",24,"fee_pct",0,"refund_pct",100,"reschedule_rule","ALLOWED","manual_approval",false,"owner_approval",false)),
  Map.entry("notifications",Map.of("status","PENDING","attempts",0)),
  Map.entry("complaints",Map.of("status","OPEN")),
  Map.entry("categories",Map.of("status","ACTIVE")),
  Map.entry("banners",Map.of("status","ACTIVE")),
  Map.entry("refund_requests",Map.of("status","PENDING"))
 );
 static final java.util.Set<String> NOW_DEFAULTS=java.util.Set.of("users","bookings","ratings","admin_actions","refund_requests");
 static final String NOW_COL="created_at";

 long insert(String table,Map<String,Object> fields){
  String pk=primary(table);
  long id=nextId(table,null);
  var doc=new LinkedHashMap<String,Object>();
  var defs=DEFAULTS.get(table);
  if(defs!=null)defs.forEach((k,v)->{if(!fields.containsKey(k))doc.put(k,v);});
  if(NOW_DEFAULTS.contains(table)&&!fields.containsKey(NOW_COL))doc.put(NOW_COL,new java.sql.Timestamp(System.currentTimeMillis()));
  if("device_tokens".equals(table)&&!fields.containsKey("updated_at"))doc.put("updated_at",new java.sql.Timestamp(System.currentTimeMillis()));
  fields.forEach((k,v)->doc.put(k,toStored(v)));
  doc.put(pk,id);
  writeDoc(firestore.collection(table).document(String.valueOf(id)),doc,false);
  return id;
 }

 /** Migration support: insert preserving the legacy numeric id. */
 long insertWithId(String table,long id,Map<String,Object> fields){
  String pk=primary(table);
  var doc=new LinkedHashMap<String,Object>();
  fields.forEach((k,v)->doc.put(k,toStored(v)));
  doc.put(pk,id);
  writeDoc(firestore.collection(table).document(String.valueOf(id)),doc,false);
  bumpCounter(table,id+1);
  return id;
 }

 void patch(String table,String pk,long id,Map<String,Object> fields){
  var data=new LinkedHashMap<String,Object>();
  fields.forEach((k,v)->data.put(k,toStored(v)));
  writeDoc(firestore.collection(table).document(String.valueOf(id)),data,true);
 }

 // ------------------------------------------------------------- internals

 record Cond(String col,String op,int argIx,String literal,List<CondIn> in){}
 record CondIn(boolean isArg,int argIx,String literal){}
 record Assign(String col,String op,int argIx,String literal,long delta){}
 record Order(String col,boolean desc){}
 record SelectQ(String table,List<String> cols,List<String> aliases,boolean star,boolean isCount,String sumCol,String sumAlias,List<Cond> where,List<Order> orderBy,int limit){}
 record Hit(DocumentReference ref,Map<String,Object> row){}
 record SelectR(List<Map<String,Object>> rows){}

 static final Set<String> DATE_COLS=Set.of("bookings.booking_date","holidays.holiday_date");
 static final Map<String,List<String>> INSERT_COLS=Map.of("staff_services",List.of("staff_id","service_id","expertise_level"));

 long nextId(String table,Transaction ignored){
  Transaction t=current.get();
  var cache=idCache.get();
  if(t!=null&&cache!=null){ // inside tx(): use pre-allocated ID, no read allowed here
   long next=cache.get(table);
   cache.put(table,next+1);
   idUsed.get().add(table);
   return next;
  }
  DocumentReference counter=firestore.collection("_counters").document(table);
  try{
   if(t!=null){
    var snap=t.get(counter).get();
    long next=snap.exists()&&snap.getLong("next")!=null?snap.getLong("next"):1L;
    t.set(counter,Map.of("next",next+1));return next;
   }
   return firestore.runTransaction(tt->{
    var snap=tt.get(counter).get();
    long next=snap.exists()&&snap.getLong("next")!=null?snap.getLong("next"):1L;
    tt.set(counter,Map.of("next",next+1));return next;
   }).get();
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
  catch(ExecutionException e){throw unwrap(e);}
 }

 void bumpCounter(String table,long atLeast){
  try{
   firestore.runTransaction(tt->{
    var ref=firestore.collection("_counters").document(table);
    var snap=tt.get(ref).get();
    long cur=snap.exists()&&snap.getLong("next")!=null?snap.getLong("next"):1L;
    if(cur<atLeast)tt.set(ref,Map.of("next",atLeast));
    return null;
   }).get();
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
  catch(ExecutionException e){throw unwrap(e);}
 }

 void writeDoc(DocumentReference ref,Map<String,Object> data,boolean merge){
  try{
   Transaction t=current.get();
   if(t!=null){if(merge)t.set(ref,data,SetOptions.merge());else t.set(ref,data);}
   else{if(merge)ref.set(data,SetOptions.merge()).get();else ref.set(data).get();}
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
  catch(ExecutionException e){throw unwrap(e);}
 }

 void deleteDoc(DocumentReference ref){
  try{
   Transaction t=current.get();
   if(t!=null)t.delete(ref);else ref.delete().get();
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
  catch(ExecutionException e){throw unwrap(e);}
 }

 List<QueryDocumentSnapshot> fetchDocs(Query q){
  try{
   Transaction t=current.get();
   QuerySnapshot snap=(t!=null?t.get(q):q.get()).get();
   return snap.getDocuments();
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
  catch(ExecutionException e){throw unwrap(e);}
 }

 // ------------------------------------------------------------- SQL parse

 static final Pattern SELECT_RE=Pattern.compile("^\\s*SELECT\\s+(.*?)\\s+FROM\\s+(\\S+?)(?:\\s+WHERE\\s+(.*?))?(?:\\s+ORDER\\s+BY\\s+(.*?))?(?:\\s+LIMIT\\s+(\\d+))?\\s*$",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);

 SelectQ parseSelect(String sql){
  String s=sql.replaceAll("(?i)\\s+FOR\\s+UPDATE\\s*$","");
  var m=SELECT_RE.matcher(s);
  if(!m.matches())throw new IllegalArgumentException("Unsupported SELECT: "+sql);
  String table=m.group(2);
  if(!table.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("No table aliases or JOINs: "+sql);
  String upper=s.toUpperCase(Locale.ROOT);
  if(upper.contains(" JOIN ")||upper.substring(6).contains("SELECT"))throw new IllegalArgumentException("No JOINs or subselects: "+sql);
  String colsPart=m.group(1).trim();
  List<String> cols=new ArrayList<>(),aliases=new ArrayList<>();
  boolean star=false,isCount=false;String sumCol=null,sumAlias=null;
  if(colsPart.equals("*"))star=true;
  else if(colsPart.matches("(?i)COUNT\\s*\\(\\s*\\*\\s*\\)"))isCount=true;
  else{
   var sm=Pattern.compile("(?i)COALESCE\\s*\\(\\s*SUM\\s*\\(\\s*(\\w+)\\s*\\)\\s*,\\s*0\\s*\\)\\s+AS\\s+(\\w+)").matcher(colsPart);
   if(sm.matches()){sumCol=sm.group(1);sumAlias=sm.group(2);}
   else for(String c:colsPart.split(",")){
    var cm=Pattern.compile("(?i)^(\\w+)(?:\\s+AS\\s+(\\w+))?$").matcher(c.trim());
    if(!cm.matches())throw new IllegalArgumentException("Unsupported column: "+c+" in "+sql);
    cols.add(cm.group(1));aliases.add(cm.group(2)!=null?cm.group(2):cm.group(1));
   }
  }
  List<Cond> where=m.group(3)!=null?parseWhere(m.group(3),0):List.of();
  List<Order> orderBy=new ArrayList<>();
  if(m.group(4)!=null)for(String o:m.group(4).split(",")){
   var om=Pattern.compile("(?i)^(\\w+)(?:\\s+(ASC|DESC))?$").matcher(o.trim());
   if(!om.matches())throw new IllegalArgumentException("Unsupported ORDER BY: "+o+" in "+sql);
   orderBy.add(new Order(om.group(1),om.group(2)!=null&&om.group(2).equalsIgnoreCase("DESC")));
  }
  int limit=m.group(5)!=null?Integer.parseInt(m.group(5)):-1;
  return new SelectQ(table,cols,aliases,star,isCount,sumCol,sumAlias,where,orderBy,limit);
 }

 /** True when every condition can be pushed to Firestore (non-null equality, inequalities on one field). */
 static boolean allFirestoreSafe(List<Cond> where,Object[] args){
  String ineqField=null;
  for(var c:where){
   if(c.op().equals("=")&&c.argIx()>=0&&args[c.argIx()]!=null)continue;
   if(isIneq(c.op())&&c.argIx()>=0&&args[c.argIx()]!=null&&(ineqField==null||ineqField.equals(c.col()))){ineqField=c.col();continue;}
   return false;
  }
  return true;
 }

 static boolean isIneq(String op){return Set.of("<","<=",">",">=").contains(op);}

 static final Pattern EQ_Q=Pattern.compile("^(\\w+)\\s*=\\s*\\?$");
 static final Pattern EQ_LIT=Pattern.compile("^(\\w+)\\s*=\\s*'([^']*)'$");
 static final Pattern EQ_NUM=Pattern.compile("^(\\w+)\\s*=\\s*(-?\\d+(?:\\.\\d+)?)$");
 static final Pattern INEQ_Q=Pattern.compile("^(\\w+)\\s*(<>|!=|<=|>=|<|>)\\s*\\?$");
 static final Pattern INEQ_NUM=Pattern.compile("^(\\w+)\\s*(<>|!=|<=|>=|<|>)\\s*(-?\\d+(?:\\.\\d+)?)$");
 static final Pattern ISNULL=Pattern.compile("^(\\w+)\\s+IS\\s+NULL$");
 static final Pattern ISNOTNULL=Pattern.compile("^(\\w+)\\s+IS\\s+NOT\\s+NULL$");
 static final Pattern IN_P=Pattern.compile("^(\\w+)\\s+IN\\s*\\((.+)\\)$");

 List<Cond> parseWhere(String w,int startIx){
  var out=new ArrayList<Cond>();int argIx=startIx;
  for(String part:w.split("(?i)\\s+AND\\s+")){
   String p=part.trim();Matcher m;
   if((m=EQ_Q.matcher(p)).matches()){out.add(new Cond(m.group(1),"=",argIx++,null,List.of()));}
   else if((m=EQ_LIT.matcher(p)).matches()){out.add(new Cond(m.group(1),"=", -1,m.group(2),List.of()));}
   else if((m=EQ_NUM.matcher(p)).matches()){out.add(new Cond(m.group(1),"=", -1,m.group(2),List.of()));}
   else if((m=INEQ_Q.matcher(p)).matches()){out.add(new Cond(m.group(1),m.group(2),argIx++,null,List.of()));}
   else if((m=INEQ_NUM.matcher(p)).matches()){out.add(new Cond(m.group(1),m.group(2),-1,m.group(3),List.of()));}
   else if((m=ISNULL.matcher(p)).matches()){out.add(new Cond(m.group(1),"IS NULL",-1,null,List.of()));}
   else if((m=ISNOTNULL.matcher(p)).matches()){out.add(new Cond(m.group(1),"IS NOT NULL",-1,null,List.of()));}
   else if((m=IN_P.matcher(p)).matches()){
    var items=new ArrayList<CondIn>();
    for(String it:m.group(2).split(",")){
     String t=it.trim();
     if(t.equals("?"))items.add(new CondIn(true,argIx++,null));
     else if(t.matches("'[^']*'"))items.add(new CondIn(false,-1,t.substring(1,t.length()-1)));
     else if(t.matches("-?\\d+(?:\\.\\d+)?"))items.add(new CondIn(false,-1,t));
     else throw new IllegalArgumentException("Unsupported IN item: "+t+" in "+w);
    }
    out.add(new Cond(m.group(1),"IN",-1,null,items));
   }
   else throw new IllegalArgumentException("Unsupported WHERE condition: "+p+" in "+w);
  }
  return out;
 }

 List<Assign> parseAssigns(String s){
  var out=new ArrayList<Assign>();int argIx=0;
  for(String part:s.split(",")){
   String p=part.trim();Matcher m;
   if((m=Pattern.compile("^(\\w+)\\s*=\\s*\\?$").matcher(p)).matches())out.add(new Assign(m.group(1),"set",argIx++,null,0));
   else if((m=Pattern.compile("^(\\w+)\\s*=\\s*'([^']*)'$").matcher(p)).matches())out.add(new Assign(m.group(1),"lit",-1,m.group(2),0));
   else if((m=Pattern.compile("^(\\w+)\\s*=\\s*(-?\\d+(?:\\.\\d+)?)$").matcher(p)).matches())out.add(new Assign(m.group(1),"lit",-1,m.group(2),0));
   else if((m=Pattern.compile("^(\\w+)\\s*=\\s*(\\w+)\\s*\\+\\s*(\\d+)$").matcher(p)).matches()){
    if(!m.group(1).equals(m.group(2)))throw new IllegalArgumentException("Unsupported assignment: "+p);
    out.add(new Assign(m.group(1),"incr",-1,null,Long.parseLong(m.group(3))));
   }
   else throw new IllegalArgumentException("Unsupported SET assignment: "+p+" in "+s);
  }
  return out;
 }

 int doInsert(String sql,Object... args){
  var m=Pattern.compile("^\\s*INSERT\\s+INTO\\s+(\\w+)\\s*(?:\\(([^)]+)\\))?\\s+VALUES\\s*\\(([^)]+)\\)\\s*$",Pattern.CASE_INSENSITIVE).matcher(sql.trim());
  if(!m.matches())throw new IllegalArgumentException("Unsupported INSERT: "+sql);
  String table=m.group(1);
  List<String> cols=m.group(2)!=null?Arrays.stream(m.group(2).split(",")).map(String::trim).toList():INSERT_COLS.get(table);
  if(cols==null)throw new IllegalArgumentException("INSERT needs a column list: "+sql);
  String[] vals=m.group(3).split(",");
  if(vals.length!=cols.size()||vals.length!=args.length)throw new IllegalArgumentException("INSERT arity mismatch: "+sql);
  var doc=new LinkedHashMap<String,Object>();
  for(int i=0;i<cols.size();i++){
   if(!vals[i].trim().equals("?"))throw new IllegalArgumentException("INSERT literals not supported: "+sql);
   doc.put(cols.get(i),toStored(normArg(args[i])));
  }
  writeDoc(firestore.collection(table).document(docIdFor(table,doc)),doc,false);
  return 1;
 }

 int doDelete(String sql,Object... args){
  var m=Pattern.compile("^\\s*DELETE\\s+FROM\\s+(\\w+)\\s+WHERE\\s+(.+?)\\s*$",Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(sql.trim());
  if(!m.matches())throw new IllegalArgumentException("Unsupported DELETE: "+sql);
  var hits=findMatches(m.group(1),parseWhere(m.group(2),0),args);
  for(var h:hits)deleteDoc(h.ref());
  return hits.size();
 }

 String docIdFor(String table,Map<String,Object> doc){
  if(table.equals("device_tokens"))return doc.get("token").toString();
  if(table.equals("staff_services"))return doc.get("staff_id")+"_"+doc.get("service_id");
  throw new IllegalArgumentException("Raw INSERT needs a doc-id rule: "+table);
 }

 // ------------------------------------------------------------- query run

 SelectR select(String sql,Object[] args,boolean forCount){
  var q=parseSelect(sql);
  Query fq=buildQuery(q.where(),q.table(),args);
  List<Map<String,Object>> rows=new ArrayList<>();
  for(var d:fetchDocs(fq)){
   var row=toRow(q.table(),d);
   if(matches(row,q.where(),args))rows.add(row);
  }
  sortRows(rows,q.orderBy());
  if(q.limit()>=0&&rows.size()>q.limit())rows=rows.subList(0,q.limit());
  if(q.isCount())return new SelectR(List.of(map("count",(long)rows.size())));
  if(q.sumCol()!=null){
   BigDecimal sum=BigDecimal.ZERO;
   for(var r:rows){Object v=r.get(q.sumCol());if(v!=null)sum=sum.add(new BigDecimal(v.toString()));}
   return new SelectR(List.of(map(q.sumAlias(),sum)));
  }
  if(!q.star()){
   var out=new ArrayList<Map<String,Object>>();
   for(var r:rows){var p=new LinkedHashMap<String,Object>();for(int i=0;i<q.cols().size();i++)p.put(q.aliases().get(i),r.get(q.cols().get(i)));out.add(p);}
   return new SelectR(out);
  }
  return new SelectR(rows);
 }

 Query buildQuery(List<Cond> where,String table,Object[] args){
  Query fq=firestore.collection(table);
  String ineqField=null;
  for(var c:where){
   if(c.op().equals("=")&&c.argIx()>=0&&args[c.argIx()]!=null){fq=fq.whereEqualTo(c.col(),toStored(normArg(args[c.argIx()])));}
   else if(isIneq(c.op())&&c.argIx()>=0&&args[c.argIx()]!=null&&(ineqField==null||ineqField.equals(c.col()))){
    ineqField=c.col();
    Object v=toStored(normArg(args[c.argIx()]));
    fq=switch(c.op()){case "<"->fq.whereLessThan(c.col(),v);case "<="->fq.whereLessThanOrEqualTo(c.col(),v);case ">"->fq.whereGreaterThan(c.col(),v);default->fq.whereGreaterThanOrEqualTo(c.col(),v);};
   }
  }
  return fq;
 }

 List<Hit> findMatches(String table,List<Cond> where,Object[] args){
  Query fq=buildQuery(where,table,args);
  var out=new ArrayList<Hit>();
  for(var d:fetchDocs(fq)){var row=toRow(table,d);if(matches(row,where,args))out.add(new Hit(d.getReference(),row));}
  return out;
 }

 Map<String,Object> toRow(String table,QueryDocumentSnapshot d){
  var row=new LinkedHashMap<String,Object>();
  for(var e:d.getData().entrySet()){
   Object v=e.getValue();
   if(v instanceof Timestamp t)v=new java.sql.Timestamp(t.getSeconds()*1000+t.getNanos()/1_000_000);
   else if(v instanceof String s&&DATE_COLS.contains(table+"."+e.getKey()))v=java.sql.Date.valueOf(s);
   row.put(e.getKey(),v);
  }
  return row;
 }

 boolean matches(Map<String,Object> row,List<Cond> conds,Object[] args){
  for(var c:conds){
   Object lv=cmpNorm(row.get(c.col()));
   switch(c.op()){
    case "="->{
     if(c.argIx()>=0&&args[c.argIx()]==null)return false; // SQL: = NULL matches nothing
     Object rv=c.argIx()>=0?cmpNorm(normArg(args[c.argIx()])):parseLiteral(c.literal());
     if(!eq(lv,rv))return false;
    }
    case "<>","!="->{
     if(c.argIx()>=0&&args[c.argIx()]==null)return false; // SQL: <> NULL matches nothing
     Object rv=c.argIx()>=0?cmpNorm(normArg(args[c.argIx()])):parseLiteral(c.literal());
     if(eq(lv,rv))return false;
    }
    case "<"->{Object rv=c.argIx()>=0?(args[c.argIx()]==null?null:cmpNorm(normArg(args[c.argIx()]))):parseLiteral(c.literal());if(rv==null)return false;if(cmp(lv,rv)>=0)return false;}
    case "<="->{Object rv=c.argIx()>=0?(args[c.argIx()]==null?null:cmpNorm(normArg(args[c.argIx()]))):parseLiteral(c.literal());if(rv==null)return false;if(cmp(lv,rv)>0)return false;}
    case ">"->{Object rv=c.argIx()>=0?(args[c.argIx()]==null?null:cmpNorm(normArg(args[c.argIx()]))):parseLiteral(c.literal());if(rv==null)return false;if(cmp(lv,rv)<=0)return false;}
    case ">="->{Object rv=c.argIx()>=0?(args[c.argIx()]==null?null:cmpNorm(normArg(args[c.argIx()]))):parseLiteral(c.literal());if(rv==null)return false;if(cmp(lv,rv)<0)return false;}
    case "IS NULL"->{if(lv!=null)return false;}
    case "IS NOT NULL"->{if(lv==null)return false;}
    case "IN"->{
     boolean hit=false;
     for(var it:c.in()){Object rv=it.isArg()?cmpNorm(normArg(args[it.argIx()])):parseLiteral(it.literal());if(eq(lv,rv)){hit=true;break;}}
     if(!hit)return false;
    }
    default->throw new IllegalArgumentException("Unsupported operator: "+c.op());
   }
  }
  return true;
 }

 Object parseLiteral(String lit){
  if(lit==null)return null;
  if(lit.matches("-?\\d+"))return Long.parseLong(lit);
  if(lit.matches("-?\\d+\\.\\d+"))return Double.parseDouble(lit);
  return lit;
 }

 static boolean eq(Object a,Object b){
  if(a==null||b==null)return a==b;
  if(a instanceof Number&&b instanceof Number)return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()))==0;
  return a.equals(b);
 }

 @SuppressWarnings({"unchecked","rawtypes"})
 static int cmp(Object a,Object b){
  if(a==null||b==null)throw new IllegalArgumentException("Cannot compare null");
  if(a instanceof Number&&b instanceof Number)return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
  if(a instanceof Comparable&&a.getClass().isInstance(b))return ((Comparable)a).compareTo(b);
  throw new IllegalArgumentException("Cannot compare "+a.getClass()+" with "+b.getClass());
 }

 void sortRows(List<Map<String,Object>> rows,List<Order> orderBy){
  if(orderBy.isEmpty())return;
  rows.sort((a,b)->{
   for(var o:orderBy){
    int c;
    try{c=cmp(cmpNorm(a.get(o.col())),cmpNorm(b.get(o.col())));}
    catch(IllegalArgumentException e){c=String.valueOf(a.get(o.col())).compareTo(String.valueOf(b.get(o.col())));}
    if(c!=0)return o.desc()?-c:c;
   }
   return 0;
  });
 }

 /** Normalize a bound argument for storage and comparison. */
 static Object normArg(Object v){
  if(v==null)return null;
  if(v instanceof Integer||v instanceof Short||v instanceof Byte)return ((Number)v).longValue();
  if(v instanceof BigDecimal bd)return bd.doubleValue();
  if(v instanceof Float)return ((Number)v).doubleValue();
  if(v instanceof java.sql.Date d)return d.toString();
  if(v instanceof java.sql.Time t)return t.toString();
  if(v instanceof java.sql.Timestamp t)return t.toInstant();
  if(v instanceof java.util.Date d)return d.toInstant();
  return v;
 }

 /** Normalize a stored/row value for comparison. */
 static Object cmpNorm(Object v){
  if(v instanceof java.sql.Date d)return d.toString();
  if(v instanceof java.sql.Timestamp t)return t.toInstant();
  if(v instanceof java.util.Date d)return d.toInstant();
  if(v instanceof Timestamp t)return Instant.ofEpochSecond(t.getSeconds(),t.getNanos());
  return normArg(v);
 }

 /** Convert a Java value to its Firestore stored form. */
 static Object toStored(Object v){
  if(v==null)return null;
  if(v instanceof Integer||v instanceof Short||v instanceof Byte)return ((Number)v).longValue();
  if(v instanceof BigDecimal bd)return bd.doubleValue();
  if(v instanceof Float)return ((Number)v).doubleValue();
  if(v instanceof java.sql.Date d)return d.toString();
  if(v instanceof java.sql.Time t)return t.toString();
  if(v instanceof java.sql.Timestamp t)return Timestamp.ofTimeSecondsAndNanos(t.getTime()/1000,(int)(t.getTime()%1000)*1_000_000);
  if(v instanceof java.util.Date d)return Timestamp.of(d);
  if(v instanceof java.time.Instant i)return Timestamp.ofTimeSecondsAndNanos(i.getEpochSecond(),i.getNano());
  if(v instanceof Boolean||v instanceof String||v instanceof Long||v instanceof Double)return v;
  if(v instanceof Map||v instanceof List)return v;
  return v.toString();
 }

 static RuntimeException unwrap(ExecutionException e){
  Throwable c=e.getCause();
  while(c!=null){
   if(c instanceof ResponseStatusException rse)return rse;
   if(c.getCause()==c)break;
   c=c.getCause();
  }
  return new IllegalStateException(e.getCause()!=null?e.getCause():e);
 }

 // ------------------------------------------------------------- helpers (unchanged)

 static String primary(String table){return switch(table){
 case "users"->"user_id";case "parlours"->"parlour_id";case "parlour_images"->"image_id";
 case "services"->"service_id";case "staff"->"staff_id";case "staff_schedules"->"schedule_id";
 case "holidays"->"holiday_id";case "blocked_slots"->"block_id";case "bookings"->"booking_id";
 case "booking_items"->"booking_item_id";case "payments"->"payment_id";case "ratings"->"rating_id";
 case "cancellation_policies"->"policy_id";case "notifications"->"notification_id";case "admin_actions"->"action_id";
 case "business_hours"->"hours_id";case "complaints"->"complaint_id";case "categories"->"category_id";
 case "banners"->"banner_id";case "refund_requests"->"refund_id";default->throw new IllegalArgumentException("Unknown generated ID table");};}
 static Map<String,Object> map(Object...args){var m=new LinkedHashMap<String,Object>();for(int i=0;i<args.length;i+=2)m.put((String)args[i],args[i+1]);return m;}
 static long id(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
 static BigDecimal money(Object x){return new BigDecimal(x.toString());}
 static boolean yes(Object x){return Boolean.TRUE.equals(x)||"1".equals(String.valueOf(x));}
 static ResponseStatusException error(int code,String msg){return new ResponseStatusException(HttpStatus.valueOf(code),msg);}
 static String text(Map<String,Object> row,String key,int max){Object v=row.get(key);if(!(v instanceof String s)||s.isBlank()||s.length()>max)throw error(400,"Invalid "+key);return s.trim();}
}
