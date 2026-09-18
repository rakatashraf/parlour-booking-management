package com.parlour.management;
import android.util.Log;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import org.json.JSONObject;

public final class FirebaseSession {
 private static final String TAG="FirebaseSession";
 private FirebaseSession(){}
 public static void sync(ApiRepository api){
  if(api.base().isEmpty()||api.token().isEmpty()){signOut();return;}
  api.request("POST","/api/firebase/custom-token",new JSONObject(),null,(value,error)->{
   if(error!=null||!(value instanceof JSONObject json)){Log.d(TAG,"Firebase session unavailable: "+error);return;}
   String token=json.optString("token","");
   if(token.isBlank())return;
   FirebaseAuth.getInstance().signInWithCustomToken(token).addOnFailureListener(e->Log.w(TAG,"Firebase sign-in failed",e));
  });
 }
 public static void signOut(){FirebaseAuth.getInstance().signOut();}
 public static FirebaseFirestore db(){return FirebaseFirestore.getInstance();}
}
