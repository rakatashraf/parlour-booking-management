package com.parlour.management;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import org.json.JSONObject;

public final class FirebaseSession {
 private static final String TAG="FirebaseSession";
 /** Persisted when custom-token sign-in keeps failing, so the next start()/saveSession() schedules a re-sync. */
 private static final String SYNC_NEEDED="firebase_sync_needed";
 private static final Handler MAIN=new Handler(Looper.getMainLooper());
 private FirebaseSession(){}
 public static void sync(Context ctx,ApiRepository api){
  if(api.base().isEmpty()||api.token().isEmpty()){signOut();return;}
  trySync(ctx.getApplicationContext(),api,0);
 }
 private static void trySync(Context appCtx,ApiRepository api,int attempt){
  api.request("POST","/api/firebase/custom-token",new JSONObject(),null,(value,error)->{
   if(error!=null||!(value instanceof JSONObject json)){Log.d(TAG,"Firebase session unavailable: "+error);scheduleRetry(appCtx,api,attempt);return;}
   String token=json.optString("token","");
   if(token.isBlank()){scheduleRetry(appCtx,api,attempt);return;}
   FirebaseAuth.getInstance().signInWithCustomToken(token)
    .addOnSuccessListener(a->prefs(appCtx).edit().remove(SYNC_NEEDED).apply())
    .addOnFailureListener(e->{Log.w(TAG,"Firebase sign-in failed",e);scheduleRetry(appCtx,api,attempt);});
  });
 }
 /** Up to 3 attempts with backoff (immediate, ~2s, ~8s), then persist the flag for the next start()/saveSession(). */
 private static void scheduleRetry(Context appCtx,ApiRepository api,int attempt){
  if(attempt>=2){prefs(appCtx).edit().putBoolean(SYNC_NEEDED,true).apply();Log.w(TAG,"Firebase sync deferred; will retry on next session save");return;}
  long delayMs=attempt==0?2000:8000;
  MAIN.postDelayed(()->trySync(appCtx,api,attempt+1),delayMs);
 }
 private static SharedPreferences prefs(Context appCtx){return appCtx.getSharedPreferences("parlour",Context.MODE_PRIVATE);}
 public static void signOut(){FirebaseAuth.getInstance().signOut();}
 public static FirebaseFirestore db(){return FirebaseFirestore.getInstance();}
}
