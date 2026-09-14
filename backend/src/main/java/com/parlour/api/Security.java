package com.parlour.api;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
@Configuration
public class Security {
 static final String ISSUER="parlour-api";
 @Bean BCryptPasswordEncoder passwords(){return new BCryptPasswordEncoder(12);}
 @Bean JwtDecoder decoder(@Value("${app.jwt-secret}")String secret){
  if(secret.getBytes(StandardCharsets.UTF_8).length<32)throw new IllegalArgumentException("JWT_SECRET needs at least 32 bytes");
  var d=NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();d.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));return d;
 }
 @Bean SecurityFilterChain chain(HttpSecurity h)throws Exception{return h.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(a->a.requestMatchers("/api/auth/**","/api/public/**","/api/payments/callback","/admin/**","/error").permitAll().anyRequest().authenticated()).oauth2ResourceServer(o->o.jwt(j->{})).addFilterBefore(new Limit(),UsernamePasswordAuthenticationFilter.class).build();}
 static class Limit extends OncePerRequestFilter {
  record Bucket(long minute,int count){} private final Map<String,Bucket> buckets=new ConcurrentHashMap<>();
  protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws java.io.IOException,ServletException{
   if(!req.getRequestURI().startsWith("/api/")||req.getMethod().equals("GET")){chain.doFilter(req,res);return;}
   long minute=System.currentTimeMillis()/60000;String key=req.getRemoteAddr()+":"+(req.getRequestURI().startsWith("/api/auth")?"auth":"write");
   if(buckets.size()>10000)buckets.entrySet().removeIf(e->e.getValue().minute()<minute);
   Bucket b=buckets.compute(key,(k,v)->v==null||v.minute()!=minute?new Bucket(minute,1):new Bucket(minute,v.count()+1));
   if(b.count()>(key.endsWith("auth")?20:120)){res.setStatus(429);res.setHeader("Retry-After","60");res.setContentType("application/json");res.getWriter().write("{\"message\":\"Too many requests. Try again shortly.\"}");return;}chain.doFilter(req,res);
  }
 }
 static long uid(){var a=SecurityContextHolder.getContext().getAuthentication();if(!(a instanceof JwtAuthenticationToken j))throw Db.error(401,"Sign in required");try{return Long.parseLong(j.getToken().getSubject());}catch(Exception e){throw Db.error(401,"Invalid identity");}}
}
