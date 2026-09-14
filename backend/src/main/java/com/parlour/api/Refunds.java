package com.parlour.api;
import static com.parlour.api.Db.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@RestController
public class Refunds {
 final Db db;final Auth auth;final Gateway gateway;final Admin admin;final TransactionTemplate tx;
 Refunds(Db db,Auth auth,Gateway gateway,Admin admin,PlatformTransactionManager manager){this.db=db;this.auth=auth;this.gateway=gateway;this.admin=admin;tx=new TransactionTemplate(manager);}
 @PostMapping("/api/admin/refunds/{id}/send") Map<String,Object> send(@PathVariable long id){long uid=auth.require("ADMIN");var r=tx.execute(s->{var row=db.one("SELECT r.*,p.bank_transaction_id,p.method FROM refund_requests r JOIN payments p ON p.payment_id=r.payment_id WHERE r.refund_id=? FOR UPDATE",id);if(!"PENDING".equals(row.get("status")))throw error(409,"Refund already submitted; reconcile instead of retrying");if(!"ONLINE".equals(row.get("method")))throw error(400,"Return cash in person");db.update("UPDATE refund_requests SET status='SENDING' WHERE refund_id=?",id);admin.audit(uid,"refund_requests",id,"SENDING","Operator approved refund");return row;});
  Map<String,Object> response;try{response=gateway.refund(r);}catch(Exception e){db.update("UPDATE refund_requests SET status='UNKNOWN' WHERE refund_id=?",id);throw error(502,"Submission outcome unknown. Check the merchant portal before taking further action.");}
  if(response.get("refund_ref_id")==null){db.update("UPDATE refund_requests SET status='UNKNOWN' WHERE refund_id=?",id);throw error(502,"Gateway reference unavailable; reconcile through merchant portal");}db.update("UPDATE refund_requests SET status='SUBMITTED',gateway_reference=? WHERE refund_id=?",response.get("refund_ref_id"),id);return map("status","SUBMITTED");
 }
 @PostMapping("/api/admin/refunds/{id}/verify") Map<String,Object> verify(@PathVariable long id,@RequestBody Map<String,Object>b){long uid=auth.require("ADMIN");var r=db.one("SELECT * FROM refund_requests WHERE refund_id=?",id);String ref=r.get("gateway_reference")==null?text(b,"gateway_reference",160):r.get("gateway_reference").toString();var verified=gateway.refundStatus(ref);if(!"refunded".equalsIgnoreCase(String.valueOf(verified.get("status")))||verified.get("refund_amount")==null||money(verified.get("refund_amount")).compareTo(money(r.get("amount")))!=0)throw error(409,"Gateway has not confirmed this refund amount");var pay=db.one("SELECT * FROM payments WHERE payment_id=?",r.get("payment_id"));if(!Objects.equals(pay.get("bank_transaction_id"),verified.get("bank_tran_id")))throw error(409,"Refund transaction mismatch");tx.executeWithoutResult(s->{db.update("UPDATE refund_requests SET status='REFUNDED',gateway_reference=? WHERE refund_id=?",ref,id);admin.audit(uid,"refund_requests",id,"REFUNDED","Verified with gateway");});return map("status","REFUNDED");}
}
