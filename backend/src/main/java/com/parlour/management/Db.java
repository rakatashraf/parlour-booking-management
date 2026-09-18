package com.parlour.management;
import java.util.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.regex.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Repository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Repository
public class Db {
 public record Mutation(String table){}
 static final Pattern MUTATION_TABLE=Pattern.compile("^\\s*(?:UPDATE\\s+|DELETE\\s+FROM\\s+|INSERT\\s+INTO\\s+)([A-Za-z_][A-Za-z0-9_]*)",Pattern.CASE_INSENSITIVE);
 final JdbcTemplate jdbc;final ApplicationEventPublisher events;
 public Db(JdbcTemplate jdbc,ApplicationEventPublisher events){this.jdbc=jdbc;this.events=events;}
 List<Map<String,Object>> list(String sql,Object... args){return jdbc.queryForList(sql,args);}
 Map<String,Object> one(String sql,Object... args){var rows=list(sql,args);if(rows.isEmpty())throw error(404,"Record not found");return rows.get(0);}
 long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
 int update(String sql,Object...args){int changed=jdbc.update(sql,args);if(changed>0)emit(sql);return changed;}
 void emit(String sql){var m=MUTATION_TABLE.matcher(sql);if(m.find())events.publishEvent(new Mutation(m.group(1).toLowerCase(Locale.ROOT)));}
 long insert(String table,Map<String,Object> fields){
  var keys=new ArrayList<>(fields.keySet());String sql="INSERT INTO "+table+" ("+String.join(",",keys)+") VALUES ("+String.join(",",Collections.nCopies(keys.size(),"?"))+")";
  KeyHolder holder=new GeneratedKeyHolder();jdbc.update(c->{var p=c.prepareStatement(sql,new String[]{primary(table)});for(int i=0;i<keys.size();i++)p.setObject(i+1,fields.get(keys.get(i)));return p;},holder);events.publishEvent(new Mutation(table));return holder.getKey().longValue();
 }
 static String primary(String table){return switch(table){
 case "users"->"user_id";case "parlours"->"parlour_id";case "parlour_images"->"image_id";
 case "services"->"service_id";case "staff"->"staff_id";case "staff_schedules"->"schedule_id";
 case "holidays"->"holiday_id";case "blocked_slots"->"block_id";case "bookings"->"booking_id";
 case "booking_items"->"booking_item_id";case "payments"->"payment_id";case "ratings"->"rating_id";
 case "cancellation_policies"->"policy_id";case "notifications"->"notification_id";case "admin_actions"->"action_id";
 case "business_hours"->"hours_id";case "complaints"->"complaint_id";case "categories"->"category_id";
 case "banners"->"banner_id";case "refund_requests"->"refund_id";default->throw new IllegalArgumentException("Unknown generated ID table");};}
 void patch(String table,String pk,long id,Map<String,Object> fields){var args=new ArrayList<>(fields.values());args.add(id);update("UPDATE "+table+" SET "+String.join(",",fields.keySet().stream().map(k->k+"=?").toList())+" WHERE "+pk+"=?",args.toArray());}
 static Map<String,Object> map(Object...args){var m=new LinkedHashMap<String,Object>();for(int i=0;i<args.length;i+=2)m.put((String)args[i],args[i+1]);return m;}
 static long id(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
 static BigDecimal money(Object x){return new BigDecimal(x.toString());}
 static boolean yes(Object x){return Boolean.TRUE.equals(x)||"1".equals(String.valueOf(x));}
 static ResponseStatusException error(int code,String msg){return new ResponseStatusException(HttpStatus.valueOf(code),msg);}
 static String text(Map<String,Object> row,String key,int max){Object v=row.get(key);if(!(v instanceof String s)||s.isBlank()||s.length()>max)throw error(400,"Invalid "+key);return s.trim();}
}
