package com.parlour.management;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import java.util.concurrent.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.*;
import org.json.*;
public final class ApiRepository {
 private final SharedPreferences prefs;private final OkHttpClient http=new OkHttpClient.Builder().callTimeout(35,TimeUnit.SECONDS).build();private final ExecutorService executor=Executors.newFixedThreadPool(3);
 public ApiRepository(Context c){prefs=c.getSharedPreferences("parlour",Context.MODE_PRIVATE);}
 public String base(){return prefs.getString("base",BuildConfig.API_BASE_URL);}
 public void setBase(String value){String s=value.replaceAll("/+$","");if(!(s.startsWith("https://")||BuildConfig.DEBUG&&s.startsWith("http://")))throw new IllegalArgumentException("Use an HTTPS backend address");String host=HttpUrl.get(s).host();if(host.isBlank())throw new IllegalArgumentException("Invalid address");if(!s.equals(base())){prefs.edit().remove("token").remove("guest").remove("user").apply();}prefs.edit().putString("base",s).apply();}
 private javax.crypto.SecretKey key()throws Exception{var ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(!ks.containsAlias("parlour-token")){var g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder("parlour-token",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}return (javax.crypto.SecretKey)ks.getKey("parlour-token",null);}
 private String encrypt(String s)throws Exception{var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());return Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);}
 public String token(){try{String v=prefs.getString("token","");if(v.isEmpty())return "";var a=v.split(":");var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(a[0],0)));return new String(c.doFinal(Base64.decode(a[1],0)),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){return "";}}
 public String role(){try{return new JSONObject(prefs.getString("user","{}")).optString("role","CUSTOMER");}catch(Exception e){return "CUSTOMER";}}
 public void session(JSONObject response)throws Exception{String encrypted=encrypt(response.getString("token"));prefs.edit().putString("token",encrypted).putString("user",response.getJSONObject("user").toString()).apply();if(role().equals("CUSTOMER"))prefs.edit().putString("guest",encrypted).apply();}
 public void logout(){String guest=prefs.getString("guest","");prefs.edit().putString("token",guest).remove("user").apply();}
 public interface Callback{void done(Object result,String error);}
 public void request(String method,String path,Object body,String idempotency,Callback callback){executor.execute(()->{try{if(base().isEmpty())throw new IllegalStateException("Set your backend address in Account → Connection");var r=new Request.Builder().url(base()+path);String t=token();if(!t.isEmpty())r.header("Authorization","Bearer "+t);if(idempotency!=null)r.header("Idempotency-Key",idempotency);r.method(method,method.equals("GET")?null:RequestBody.create(body==null?"{}":body.toString(),MediaType.get("application/json")));try(var response=http.newCall(r.build()).execute()){String raw=response.body()==null?"{}":response.body().string();Object parsed=new JSONTokener(raw).nextValue();if(!response.isSuccessful()){String error=parsed instanceof JSONObject o?o.optString("message","Request failed ("+response.code()+")"):"Request failed ("+response.code()+")";throw new IllegalStateException(error);}callback.done(parsed,null);}}catch(Exception e){callback.done(null,e.getMessage()==null?"Connection failed":e.getMessage());}});}
}
