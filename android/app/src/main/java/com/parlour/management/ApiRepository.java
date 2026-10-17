package com.parlour.management;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.security.keystore.*;
import android.util.Base64;
import android.net.Uri;
import java.io.*;
import java.security.KeyStore;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.*;
import org.json.*;
public final class ApiRepository {
 private final Context appContext;private final SharedPreferences prefs;private final OkHttpClient http=new OkHttpClient.Builder().callTimeout(35,TimeUnit.SECONDS).addInterceptor(new RetryInterceptor()).build();private final ExecutorService executor=Executors.newFixedThreadPool(3);
 public ApiRepository(Context c){appContext=c.getApplicationContext();prefs=appContext.getSharedPreferences("parlour",Context.MODE_PRIVATE);}
 /** Retry idempotent GET requests with exponential backoff: 3 attempts (0s, 1s, 2s).
     Non-idempotent requests (POST/PUT/DELETE, e.g. bookings/payments) are never
     auto-retried — they rely on the Idempotency-Key header instead. */
 private static final class RetryInterceptor implements Interceptor{
  @Override public Response intercept(Chain chain)throws IOException{
   Request req=chain.request();boolean idempotent="GET".equalsIgnoreCase(req.method());IOException last=null;
   for(int attempt=0;attempt<3;attempt++){
    if(attempt>0){try{Thread.sleep(attempt==1?1000:2000);}catch(InterruptedException ie){Thread.currentThread().interrupt();throw new IOException("Retry interrupted",ie);}}
    try{
     Response resp=chain.proceed(req);
     if(idempotent&&(resp.code()==502||resp.code()==503||resp.code()==504)){resp.close();continue;}
     return resp;
    }catch(IOException e){last=e;if(!idempotent)throw e;}
   }
   throw last!=null?last:new IOException("Request failed after retries");
  }
 }
 /** Listener invoked when the backend reports 401 (session expired/invalid).
     Implemented by the UI layer to navigate to the login screen. */
 public interface AuthListener{void onSessionExpired();}
 private volatile AuthListener authListener;private final AtomicBoolean authNotified=new AtomicBoolean(false);
 public void setAuthListener(AuthListener l){authListener=l;}
 public String base(){return prefs.getString("base",BuildConfig.API_BASE_URL);}
 public void setBase(String value){String s=value.replaceAll("/+$","");if(!(s.startsWith("https://")||BuildConfig.DEBUG&&s.startsWith("http://")))throw new IllegalArgumentException("Use an HTTPS backend address");String host=HttpUrl.get(s).host();if(host.isBlank())throw new IllegalArgumentException("Invalid address");if(!s.equals(base())){prefs.edit().remove("token").remove("guest").remove("user").apply();}prefs.edit().putString("base",s).apply();}
 private javax.crypto.SecretKey key()throws Exception{var ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(!ks.containsAlias("parlour-token")){var g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder("parlour-token",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}return (javax.crypto.SecretKey)ks.getKey("parlour-token",null);}
 private String encrypt(String s)throws Exception{var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());return Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);}
 private String decrypt(String v)throws Exception{var a=v.split(":");var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(a[0],0)));return new String(c.doFinal(Base64.decode(a[1],0)),java.nio.charset.StandardCharsets.UTF_8);}
 public String token(){try{String v=prefs.getString("token","");if(v.isEmpty())return "";return decrypt(v);}catch(Exception e){return "";}}
 /** Decrypts the stored user JSON. Tolerates legacy plaintext installs. */
 private String userJson(){String v=prefs.getString("user","{}");if(v.isEmpty()||v.startsWith("{"))return v;try{return decrypt(v);}catch(Exception e){return "{}";}}
 public String role(){try{return new JSONObject(userJson()).optString("role","CUSTOMER");}catch(Exception e){return "CUSTOMER";}}
 public void session(JSONObject response)throws Exception{String encrypted=encrypt(response.getString("token"));prefs.edit().putString("token",encrypted).putString("user",encrypt(response.getJSONObject("user").toString())).apply();authNotified.set(false);if(role().equals("CUSTOMER"))prefs.edit().putString("guest",encrypted).apply();}
 /** Best-effort server-side logout (POST /api/auth/logout unregisters this
     device's push tokens; JWTs themselves are stateless). Never fails the
     local logout if the network call fails. */
 public void logout(){String t=token();if(!base().isEmpty()&&!t.isEmpty()){executor.execute(()->{try{var req=new Request.Builder().url(base()+"/api/auth/logout").post(RequestBody.create("{}",MediaType.get("application/json"))).header("Authorization","Bearer "+t).build();try(var r=http.newCall(req).execute()){/* best-effort: result ignored */}}catch(Exception ignored){}});}String guest=prefs.getString("guest","");prefs.edit().putString("token",guest).remove("user").apply();}
 private void onUnauthorized(){String guest=prefs.getString("guest","");prefs.edit().putString("token",guest).remove("user").apply();if(authNotified.compareAndSet(false,true)){AuthListener l=authListener;if(l!=null)l.onSessionExpired();}}
 /** Returns false when the device has no validated internet connection. */
 private boolean isOnline(){try{var cm=(ConnectivityManager)appContext.getSystemService(Context.CONNECTIVITY_SERVICE);if(cm==null)return true;var caps=cm.getNetworkCapabilities(cm.getActiveNetwork());return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);}catch(Exception e){return true;}}
 public interface Callback{void done(Object result,String error);}
 public void uploadImage(Context context,String path,Uri uri,Callback callback){executor.execute(()->{try{if(base().isEmpty())throw new IllegalStateException("Set your backend address in Account → Connection");if(!isOnline())throw new IllegalStateException("You are offline. Check your connection and try again.");String mime=context.getContentResolver().getType(uri);if(!"image/jpeg".equals(mime)&&!"image/png".equals(mime))throw new IllegalArgumentException("Choose a JPEG or PNG image");byte[] bytes;try(var in=context.getContentResolver().openInputStream(uri);var out=new ByteArrayOutputStream()){if(in==null)throw new FileNotFoundException("Image could not be opened");byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>5*1024*1024)throw new IllegalArgumentException("Image must be below 5 MB");}bytes=out.toByteArray();}var multipart=new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file","parlour."+("image/png".equals(mime)?"png":"jpg"),RequestBody.create(bytes,MediaType.get(mime))).build();var r=new Request.Builder().url(base()+path).post(multipart);String t=token();if(!t.isEmpty())r.header("Authorization","Bearer "+t);try(var response=http.newCall(r.build()).execute()){String raw=response.body()==null?"{}":response.body().string();Object parsed=new JSONTokener(raw).nextValue();if(response.code()==401){onUnauthorized();throw new IllegalStateException("Session expired, please sign in again");}if(!response.isSuccessful()){String error=parsed instanceof JSONObject o?o.optString("message","Upload failed ("+response.code()+")"):"Upload failed ("+response.code()+")";throw new IllegalStateException(error);}callback.done(parsed,null);}}catch(Exception e){callback.done(null,e.getMessage()==null?"Upload failed":e.getMessage());}});}
 public void request(String method,String path,Object body,String idempotency,Callback callback){executor.execute(()->{try{if(base().isEmpty())throw new IllegalStateException("Set your backend address in Account → Connection");if(!isOnline())throw new IllegalStateException("You are offline. Check your connection and try again.");var r=new Request.Builder().url(base()+path);String t=token();if(!t.isEmpty())r.header("Authorization","Bearer "+t);if(idempotency!=null)r.header("Idempotency-Key",idempotency);r.method(method,method.equals("GET")?null:RequestBody.create(body==null?"{}":body.toString(),MediaType.get("application/json")));try(var response=http.newCall(r.build()).execute()){String raw=response.body()==null?"{}":response.body().string();Object parsed=new JSONTokener(raw).nextValue();if(response.code()==401){onUnauthorized();throw new IllegalStateException("Session expired, please sign in again");}if(!response.isSuccessful()){String error=parsed instanceof JSONObject o?o.optString("message","Request failed ("+response.code()+")"):"Request failed ("+response.code()+")";throw new IllegalStateException(error);}callback.done(parsed,null);}}catch(Exception e){callback.done(null,e.getMessage()==null?"Connection failed":e.getMessage());}});}
}
