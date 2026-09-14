package com.parlour.api;
import static com.parlour.api.Db.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
@RestController
public class Auth {
 final Db db;final BCryptPasswordEncoder encoder;final String secret;
 Auth(Db db,BCryptPasswordEncoder encoder,@Value("${app.jwt-secret}")String secret){this.db=db;this.encoder=encoder;this.secret=secret;}
 Map<String,Object> current(){var u=db.one("SELECT user_id,name,email,phone,role,status FROM users WHERE user_id=?",Security.uid());if(!"ACTIVE".equals(u.get("status")))throw error(403,"Account is not active");return u;}
 long require(String role){var u=current();if(!role.equals(u.get("role")))throw error(403,"This action requires "+role);return id(u,"user_id");}
 void owner(long pid){long uid=require("OWNER");if(db.count("SELECT COUNT(*) FROM parlours WHERE parlour_id=? AND owner_id=?",pid,uid)!=1)throw error(403,"Not your parlour");}
 @PostMapping("/api/auth/guest") Map<String,Object> guest(){return token(db.insert("users",map("name","Guest","role","CUSTOMER","status","ACTIVE")));}
 @PostMapping("/api/auth/register") Map<String,Object> register(@RequestBody Map<String,Object> b){String email=text(b,"email",254).toLowerCase(Locale.ROOT),password=text(b,"password",72);validatePassword(password);if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw error(400,"Enter a valid email");return token(db.insert("users",map("name",text(b,"name",120),"email",email,"phone",phone(b),"password_hash",encoder.encode(password),"role","OWNER","status","ACTIVE")));}
 @PostMapping("/api/auth/login") Map<String,Object> login(@RequestBody Map<String,Object>b){var rows=db.list("SELECT * FROM users WHERE email=?",text(b,"email",254).toLowerCase(Locale.ROOT));String password=text(b,"password",72);if(rows.isEmpty()||rows.get(0).get("password_hash")==null||!encoder.matches(password,rows.get(0).get("password_hash").toString())||!"ACTIVE".equals(rows.get(0).get("status")))throw error(401,"Invalid credentials");return token(id(rows.get(0),"user_id"));}
 @GetMapping("/api/me") Map<String,Object> me(){return current();}
 static String phone(Map<String,Object>b){String p=text(b,"phone",30);if(!p.matches("\\+?[0-9 ()-]{7,25}"))throw error(400,"Invalid phone");return p;}
 static void validatePassword(String p){if(p.length()<12||p.getBytes(StandardCharsets.UTF_8).length>72)throw error(400,"Password must contain at least 12 characters and at most 72 UTF-8 bytes");}
 Map<String,Object> token(long uid) {
  try {
   var user=db.one("SELECT user_id,name,role FROM users WHERE user_id=?",uid);
   Instant now=Instant.now();
   Instant expiry=now.plus(Duration.ofDays("CUSTOMER".equals(user.get("role"))?90:1));
   var claims=new JWTClaimsSet.Builder().issuer(Security.ISSUER).subject(""+uid)
    .issueTime(Date.from(now)).expirationTime(Date.from(expiry)).build();
   var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),claims);
   jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
   return map("token",jwt.serialize(),"user",user);
  } catch(Exception e) { throw new IllegalStateException("Could not issue session",e); }
 }
 @Bean ApplicationRunner bootstrap(@Value("${app.admin-email}")String email,@Value("${app.admin-password}")String password){return args->{if(!email.isBlank()&&db.count("SELECT COUNT(*) FROM users WHERE role='ADMIN'")==0){validatePassword(password);db.insert("users",map("name","Platform admin","email",email.toLowerCase(Locale.ROOT),"password_hash",encoder.encode(password),"role","ADMIN","status","ACTIVE"));}};}
}
