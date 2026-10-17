package com.parlour.management;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.messaging.*;
import org.json.JSONObject;
import java.util.Map;
public class PushService extends FirebaseMessagingService {
 @Override public void onNewToken(String token){ApiRepository api=new ApiRepository(this);if(!api.token().isEmpty())api.request("POST","/api/device-token",MainActivity.obj("token",token),null,(a,b)->{});}
 @Override public void onMessageReceived(RemoteMessage message){NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("bookings","Booking updates",NotificationManager.IMPORTANCE_DEFAULT));if(android.os.Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
  // Support both notification payloads and data-only payloads (e.g. {"title":..,"body":..} or {"type":"booking_update",...}).
  var n=message.getNotification();
  String title=n!=null?n.getTitle():null,body=n!=null?n.getBody():null;
  Map<String,String> data=message.getData();
  if(data!=null&&!data.isEmpty()){
   if(title==null)title=data.get("title");
   if(body==null)body=data.get("body");
   if(title==null){String type=data.get("type");if(type!=null&&!type.isBlank())title=type.replace('_',' ');}
  }
  if((title==null||title.isBlank())&&(body==null||body.isBlank()))return; // nothing to show
  if(title==null||title.isBlank())title="Parlour Booking";
  if(body==null)body="";
  Intent open=new Intent(this,MainActivity.class);
  if(data!=null&&data.get("booking_id")!=null)open.putExtra("booking_id",data.get("booking_id"));
  PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  NotificationManagerCompat.from(this).notify((int)(System.currentTimeMillis()%Integer.MAX_VALUE),new NotificationCompat.Builder(this,"bookings").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setContentIntent(pi).setAutoCancel(true).build());}
}
