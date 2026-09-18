package com.parlour.management;
import static com.parlour.management.Db.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class Gateway {
 final String store,password,base,publicUrl;final ObjectMapper json;
 final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
 Gateway(ObjectMapper json,@Value("${app.ssl-store-id}")String store,@Value("${app.ssl-store-password}")String password,@Value("${app.ssl-live}")boolean live,@Value("${app.public-url}")String publicUrl){this.json=json;this.store=store;this.password=password;this.publicUrl=publicUrl.replaceAll("/$","");base=live?"https://securepay.sslcommerz.com":"https://sandbox.sslcommerz.com";}
 Map<String,Object> initiate(Map<String,Object> booking,String tx,Map<String,Object>billing){if(!publicUrl.startsWith("https://"))throw error(503,"Public HTTPS callback URL is required");var p=map("total_amount",booking.get("advance_amount"),"currency","BDT","tran_id",tx,"success_url",publicUrl+"/api/payments/callback","fail_url",publicUrl+"/api/payments/callback","cancel_url",publicUrl+"/api/payments/callback","ipn_url",publicUrl+"/api/payments/callback","shipping_method","NO","product_name","Parlour appointment","product_category","Service","product_profile","non-physical-goods","cus_name",booking.get("customer_name"),"cus_phone",booking.get("customer_phone"),"cus_email",text(billing,"email",254),"cus_add1",text(billing,"address",500),"cus_city",text(billing,"city",120),"cus_country",text(billing,"country",80));return request("/gwprocess/v4/api.php",p,true);}
 Map<String,Object> verify(String val){return request("/validator/api/validationserverAPI.php",map("val_id",val),false);}
 Map<String,Object> refund(Map<String,Object> r){return request("/validator/api/merchantTransIDvalidationAPI.php",map("bank_tran_id",r.get("bank_transaction_id"),"refund_amount",r.get("amount"),"refund_remarks",r.get("reason"),"refe_id","refund-"+r.get("refund_id")),false);}
 Map<String,Object> refundStatus(String ref){return request("/validator/api/merchantTransIDvalidationAPI.php",map("refund_ref_id",ref),false);}
 @SuppressWarnings("unchecked") Map<String,Object> request(String path,Map<String,Object> data,boolean post){if(store.isBlank()||password.isBlank())throw error(503,"Gateway credentials are not configured");data.put("store_id",store);data.put("store_passwd",password);data.put("format","json");String body=String.join("&",data.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(String.valueOf(e.getValue()),StandardCharsets.UTF_8)).toList());try{var request=HttpRequest.newBuilder(URI.create(base+path+(post?"":"?"+body))).timeout(Duration.ofSeconds(25));if(post)request.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));else request.GET();var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new IllegalStateException();return json.readValue(response.body(),Map.class);}catch(Exception e){throw error(502,"Payment gateway did not return a valid response");}}
}
