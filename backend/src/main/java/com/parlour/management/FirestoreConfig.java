package com.parlour.management;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import java.util.Map;

/** Firestore is the only database. This wires it up, failing fast without credentials. */
@Configuration
public class FirestoreConfig {
 /** Set FIRESTORE_EMULATOR_HOST=localhost:8080 to run against the local emulator (tests). */
 @Bean
 Firestore firestore() throws Exception{
  String emulator=System.getenv("FIRESTORE_EMULATOR_HOST");
  if(emulator!=null&&!emulator.isBlank()){
   var noAuth=new com.google.auth.Credentials(){
    public String getAuthenticationType(){return "OAuth2";}
    public Map<String,List<String>> getRequestMetadata(java.net.URI uri){return Map.of();}
    public boolean hasRequestMetadata(){return false;}
    public boolean hasRequestMetadataOnly(){return true;}
    public void refresh(){}
   };
   return FirestoreOptions.getDefaultInstance().toBuilder()
    .setProjectId("parlour-booking-test")
    .setCredentialsProvider(com.google.api.gax.core.FixedCredentialsProvider.create(noAuth))
    .setEmulatorHost(emulator)
    .build().getService();
  }
  FirebaseApp.initializeApp(FirebaseOptions.builder()
   .setProjectId("parlour-booking-management")
   .setCredentials(GoogleCredentials.getApplicationDefault())
   .build());
  return FirestoreClient.getFirestore();
 }

 @Bean
 FirebaseApp firebaseApp(Firestore firestore){
  // Production: firestore() already initialized the default app above.
  // Emulator: create a credential-less app for Messaging/Auth/Storage beans.
  try{return FirebaseApp.getInstance();}
  catch(IllegalStateException e){
   return FirebaseApp.initializeApp(FirebaseOptions.builder()
    .setProjectId("parlour-booking-test")
    .setCredentials(GoogleCredentials.create(new AccessToken("emulator-dummy",null))).build());
  }
 }
}
