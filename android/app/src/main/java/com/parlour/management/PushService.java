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
public class PushService extends FirebaseMessagingService {
 @Override public void onNewToken(String token){ApiRepository api=new ApiRepository(this);if(!api.token().isEmpty())api.request("POST","/api/device-token",MainActivity.obj("token",token),null,(a,b)->{});}
 @Override public void onMessageReceived(RemoteMessage message){NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("bookings","Booking updates",NotificationManager.IMPORTANCE_DEFAULT));if(android.os.Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;var n=message.getNotification();if(n==null)return;PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);NotificationManagerCompat.from(this).notify((int)(System.currentTimeMillis()%Integer.MAX_VALUE),new NotificationCompat.Builder(this,"bookings").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(n.getTitle()).setContentText(n.getBody()).setContentIntent(open).setAutoCancel(true).build());}
}
