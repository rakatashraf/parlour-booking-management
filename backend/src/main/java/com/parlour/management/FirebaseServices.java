package com.parlour.management;
import static com.parlour.management.Db.*;
import java.util.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.*;
import com.google.firebase.messaging.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.StorageClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
@RestController
public class FirebaseServices {
 final Db db;final Auth auth;final Catalog catalog;final boolean enabled;FirebaseApp app;
 FirebaseServices(Db db,Auth auth,Catalog catalog,@Value("${app.firebase-enabled}")boolean enabled,@Value("${app.firebase-project}")String project,@Value("${app.firebase-bucket}")String bucket)throws Exception{this.db=db;this.auth=auth;this.catalog=catalog;this.enabled=enabled;if(enabled)app=FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(GoogleCredentials.getApplicationDefault()).setProjectId(project).setStorageBucket(bucket).build(),"parlour");}
 @PostMapping("/api/firebase/custom-token") Map<String,Object> firebaseToken(){if(!enabled)throw error(503,"Firebase is not configured on the backend");var user=auth.current();String uid=Long.toString(id(user,"user_id"));try{String token=FirebaseAuth.getInstance(app).createCustomToken(uid,Map.of("role",user.get("role").toString()));return map("token",token,"uid",uid,"role",user.get("role"));}catch(Exception e){throw error(503,"Firebase authentication is unavailable");}}
 @PostMapping("/api/device-token") @Transactional Map<String,Object> device(@RequestBody Map<String,Object>b){long uid=id(auth.current(),"user_id");String token=text(b,"token",512);db.update("DELETE FROM device_tokens WHERE token=?",token);db.update("INSERT INTO device_tokens(token,user_id) VALUES (?,?)",token,uid);return map("ok",true);}
 @GetMapping("/api/notifications") List<Map<String,Object>> inbox(){return db.list("SELECT notification_id,booking_id,type,title,body,status,sent_at FROM notifications WHERE user_id=? ORDER BY notification_id DESC LIMIT 100",id(auth.current(),"user_id"));}
 @Scheduled(fixedDelay=60000) void send(){if(!enabled)return;for(var n:db.list("SELECT * FROM notifications WHERE status='PENDING' AND attempts<10 ORDER BY notification_id LIMIT 30")){long nid=id(n,"notification_id");boolean success=true;var tokens=db.list("SELECT token FROM device_tokens WHERE user_id=?",n.get("user_id"));if(tokens.isEmpty())continue;for(var t:tokens){try{FirebaseMessaging.getInstance(app).send(Message.builder().setToken(t.get("token").toString()).setNotification(Notification.builder().setTitle(n.get("title").toString()).setBody(n.get("body").toString()).build()).build());}catch(FirebaseMessagingException e){if(e.getMessagingErrorCode()==MessagingErrorCode.UNREGISTERED)db.update("DELETE FROM device_tokens WHERE token=?",t.get("token"));else success=false;}}db.update("UPDATE notifications SET status=?,attempts=attempts+1,sent_at=? WHERE notification_id=?",success?"SENT":"PENDING",success?Timestamp.from(Instant.now()):null,nid);}}
 @PostMapping("/api/owner/parlours/{pid}/upload") Map<String,Object> upload(@PathVariable long pid,@RequestParam("file")MultipartFile file)throws Exception{auth.owner(pid);if(!enabled)throw error(503,"Firebase Storage is not configured yet");byte[] bytes=file.getBytes();if(bytes.length>5*1024*1024||bytes.length<8)throw error(400,"Image must be below 5 MB");String mime=bytes[0]==(byte)0x89&&bytes[1]==0x50&&bytes[2]==0x4e&&bytes[3]==0x47?"image/png":bytes[0]==(byte)0xff&&bytes[1]==(byte)0xd8?"image/jpeg":null;if(mime==null)throw error(400,"Only JPEG and PNG images are supported");
 try(var in=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))){var readers=javax.imageio.ImageIO.getImageReaders(in);if(!readers.hasNext())throw error(400,"Invalid image");var reader=readers.next();try{reader.setInput(in);if((long)reader.getWidth(0)*reader.getHeight(0)>25000000)throw error(400,"Image dimensions are too large");}finally{reader.dispose();}}
 var bucket=StorageClient.getInstance(app).bucket();String key="parlours/"+pid+"/"+UUID.randomUUID(),token=UUID.randomUUID().toString();var blob=bucket.create(key,bytes,mime);blob.toBuilder().setMetadata(Map.of("firebaseStorageDownloadTokens",token)).build().update();String url="https://firebasestorage.googleapis.com/v0/b/"+bucket.getName()+"/o/"+URLEncoder.encode(key,StandardCharsets.UTF_8)+"?alt=media&token="+token;return map("image_url",url);
 }
}
