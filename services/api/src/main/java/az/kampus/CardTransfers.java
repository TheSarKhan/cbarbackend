package az.kampus;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import static az.kampus.Store.*;

/** Durable local transfer state; the mock network sees tokens, never API-side PAN. */
@Service
public class CardTransfers {
    final Store s;final Ledger l;final Auth auth;final Simulator sim;final OpenBanking openBanking;
    final long reviewAfter;
    CardTransfers(Store s,Ledger l,Auth auth,Simulator sim,OpenBanking openBanking,@Value("${kampus.oct-review-after-ms:60000}")long reviewAfter){this.s=s;this.l=l;this.auth=auth;this.sim=sim;this.openBanking=openBanking;this.reviewAfter=reviewAfter;}
    Object write(Auth.User u,String path,Map<String,Object>b,String proof){
        if(path.matches("recipient-cards/[^/]+/delete")){
            var card=s.own("RECIPIENT_CARD",path.split("/")[1],u.id());
            s.db.update("DELETE FROM records WHERE kind='RECIPIENT_CARD' AND id=?",card.get("id"));
            return m("deleted",true,"id",card.get("id"));
        }
        if(path.equals("card-transfers/card")){
            String number=str(b,"number").replaceAll("[ -]","");
            Problem.require(b.keySet().equals(Set.of("number"))&&number.matches("\\d{16}")&&Engine.luhn(number),"INVALID_CARD","Kart nömrəsi yanlışdır.");
            var remote=sim.call("/cards/tokenize",m("pan",number));
            s.add("CARD_SESSION",u.id(),m("id",remote.get("sessionId"),"formPath",""));
            return m("sessionId",remote.get("sessionId"),"bankName",remote.get("bankName"),"brand",remote.get("brand"),"last4",remote.get("last4"));
        }
        if(path.matches("card-transfers/[^/]+/cancel")){
            var t=s.own("CARD_TRANSFER",path.split("/")[1],u.id());
            Problem.require("SCHEDULED".equals(str(t,"status"))&&num(t,"executeAt")>System.currentTimeMillis(),"CANNOT_CANCEL","Ləğv etmə müddəti bitib.");
            long total=num(t,"amountMinor")+num(t,"feeMinor");
            l.release(str(t,"holdId"));
            // money that came from a shared bank card goes back to that card, not into the wallet
            if(!str(t,"cardChargeId").isBlank()&&openBanking.refundCharge(u.id(),str(t,"cardConsentRef"),str(t,"cardChargeId"),str(t,"cardAccountId"),total)){
                l.post("BANK_CARD_REFUND","Bank kartına qaytarma","wallet:"+u.id(),"CUSTOMER_FUNDS_BANK",total);t.put("returnedToCard",true);
            }
            t.put("status","CANCELLED");s.save("CARD_TRANSFER",t);s.audit(u.id(),"CARD_TRANSFER_CANCELLED",publicTransfer(t));
            return publicTransfer(t);
        }
        Set<String> fields=path.equals("card-transfers/card-session")?Set.of():path.equals("card-transfers/fee-quote")?Set.of("sessionId","cardId","amountMinor","purpose"):Set.of("quoteId","amountMinor","totalMinor","recipientName","saveAs","isOwn","fundingAccountId");
        Problem.require(fields.containsAll(b.keySet()),"UNEXPECTED_FIELD","Kart nömrəsini yalnız mock kart formasına daxil edin.");
        if(path.equals("card-transfers/card-session")){
            var remote=sim.call("/cards/sessions",m());
            return s.add("CARD_SESSION",u.id(),m("id",remote.get("sessionId"),"formPath",remote.get("formPath")));
        }
        if(path.equals("card-transfers/fee-quote")){
            long a=amount(b);Problem.require(a<=100000,"TRANSFER_LIMIT","Maksimum köçürmə 1 000 AZN-dir.");
            Map<String,Object> card;
            if(!str(b,"cardId").isBlank()){
                card=s.own("RECIPIENT_CARD",str(b,"cardId"),u.id());
            }else{
                String sessionId=str(b,"sessionId");s.own("CARD_SESSION",sessionId,u.id());
                card=sim.call("/cards/session-status",m("sessionId",sessionId));
                Problem.require(str(card,"status").equals("COMPLETED"),"CARD_NOT_READY","Demo kart formasını tamamlayın.");
            }
            boolean own="WITHDRAW_OWN".equals(str(b,"purpose"))||yes(card,"isOwn")&&!"P2P".equals(str(b,"purpose"));
            // card transfers carry no commission
            long fee=0;
            return s.add("CARD_QUOTE",u.id(),m("amountMinor",a,"feeMinor",fee,"totalMinor",a+fee,"purpose",own?"WITHDRAW_OWN":"P2P","cardToken",card.get("cardToken"),"bin",card.getOrDefault("bin","424242"),"last4",card.get("last4"),"bankName",card.get("bankName"),"brand",card.getOrDefault("brand","VISA"),"expiresAt",System.currentTimeMillis()+120000,"used",false));
        }
        if(path.equals("card-transfers")){
            var q=s.own("CARD_QUOTE",str(b,"quoteId"),u.id());
            Problem.require(!yes(q,"used")&&num(q,"expiresAt")>System.currentTimeMillis(),"QUOTE_EXPIRED","Komissiya təklifinin vaxtı bitib.");
            Problem.require(amount(b)==num(q,"amountMinor"),"AMOUNT_MISMATCH","Məbləğ təklifə uyğun deyil.");
            if(b.containsKey("totalMinor"))Problem.require(num(b,"totalMinor")==num(q,"totalMinor"),"AMOUNT_MISMATCH","Komissiya dəyişdirilə bilməz.");
            if(b.containsKey("recipientName"))Problem.require(str(b,"recipientName").equals(str(q,"bankName")+" · •••• "+str(q,"last4")),"RECIPIENT_MISMATCH","Alan dəyişdirilə bilməz.");
            auth.requireSca(u,proof,path,b);
            if(!str(b,"saveAs").isBlank()&&s.list("RECIPIENT_CARD",u.id()).stream().noneMatch(x->str(x,"last4").equals(str(q,"last4"))&&str(x,"bin").equals(String.valueOf(q.getOrDefault("bin","424242"))))) {
                s.add("RECIPIENT_CARD",u.id(),m("nickname",str(b,"saveAs"),"isOwn",yes(b,"isOwn")||"WITHDRAW_OWN".equals(str(q,"purpose")),"cardToken",q.get("cardToken"),"bin",q.getOrDefault("bin","424242"),"last4",q.get("last4"),"bankName",q.get("bankName"),"brand",q.getOrDefault("brand","VISA")));
            }
            long total=num(q,"totalMinor");
            // paying from a shared bank card: the bank takes the total first and it becomes wallet money held for the transfer
            String funding=str(b,"fundingAccountId");Map<String,Object> charge=m();
            if(!funding.isBlank()){charge=openBanking.charge(u.id(),funding,total,"Karta köçürmə");l.post("BANK_CARD_FUNDING","Bank kartından maliyyələşmə","CUSTOMER_FUNDS_BANK","wallet:"+u.id(),total);}
            String hold=l.hold("wallet:"+u.id(),total);
            var t=s.add("CARD_TRANSFER",u.id(),m("userId",u.id(),"amountMinor",q.get("amountMinor"),"feeMinor",q.get("feeMinor"),"totalMinor",total,"purpose",q.getOrDefault("purpose","P2P"),"bin",q.getOrDefault("bin","424242"),"last4",q.get("last4"),"bankName",q.get("bankName"),"brand",q.getOrDefault("brand","VISA"),"cardToken",q.get("cardToken"),"holdId",hold,"status","SCHEDULED","executeAt",System.currentTimeMillis()+5000,"deadlineAt",System.currentTimeMillis()+5000+reviewAfter,"reviewExpiresAt",System.currentTimeMillis()+1800000));
            if(!funding.isBlank()){t.put("fundedBy",charge.get("cardLabel"));t.put("cardChargeId",charge.get("chargeId"));t.put("cardConsentRef",charge.get("simConsentId"));t.put("cardAccountId",charge.get("accountId"));s.save("CARD_TRANSFER",t);}
            q.put("used",true);s.save("CARD_QUOTE",q);return publicTransfer(t);
        }
        throw new Problem(404,"NOT_FOUND","Kart köçürməsi tapılmadı.");
    }
    Object read(Auth.User u,String path){
        if(path.equals("recipient-cards"))return s.list("RECIPIENT_CARD",u.id()).stream().map(this::publicCard).toList();
        if(path.equals("card-transfers"))return s.list("CARD_TRANSFER",u.id()).stream().map(this::publicTransfer).toList();
        if(path.startsWith("card-sessions/")){
            String id=path.substring("card-sessions/".length());s.own("CARD_SESSION",id,u.id());
            var status=new LinkedHashMap<>(sim.call("/cards/session-status",m("sessionId",id)));status.remove("cardToken");return status;
        }
        return publicTransfer(s.own("CARD_TRANSFER",path.substring("card-transfers/".length()),u.id()));
    }
    Map<String,Object> publicCard(Map<String,Object> c){var out=new LinkedHashMap<>(c);out.remove("cardToken");return out;}
    Map<String,Object> publicTransfer(Map<String,Object> t){var out=new LinkedHashMap<>(t);out.remove("cardToken");out.remove("holdId");out.remove("cardChargeId");out.remove("cardConsentRef");out.remove("cardAccountId");return out;}
    void notify(Map<String,Object> t,String title){s.add("NOTIFICATION",str(t,"userId"),m("title",title,"body",str(t,"bankName")+" · •••• "+str(t,"last4"),"read",false));}
    void finish(Map<String,Object> t,boolean approved){
        String hold=str(t,"holdId"),uid=str(t,"userId");long a=num(t,"amountMinor"),fee=num(t,"feeMinor");
        if(approved){
            int captured=s.db.update("UPDATE holds SET status='CAPTURED' WHERE id=? AND status='ACTIVE'",hold);
            Problem.require(captured==1,"INVALID_HOLD","Rezerv artıq tamamlanıb.");
            l.post("CARD_TRANSFER","Mock karta köçürmə","wallet:"+uid,"CARD_SETTLEMENT",a);
            if(fee>0)l.post("CARD_TRANSFER_FEE","Kart köçürmə komissiyası","wallet:"+uid,"FEE_INCOME",fee);
            l.post("CARD_SETTLEMENT","Mock bank hesablaşması","CARD_SETTLEMENT","CUSTOMER_FUNDS_BANK",a);
            if(fee>0)l.post("FEE_SWEEP","Komissiya ayrılması","OWN_FUNDS_BANK","CUSTOMER_FUNDS_BANK",fee);
            s.add("TX",uid,m("type","CARD_TRANSFER","name",str(t,"bankName")+" · •••• "+str(t,"last4"),"amountMinor",a+fee,"direction","OUT","category","Köçürmələr","status","COMPLETED","businessRef",t.get("id")));
        }else{
            l.release(hold);
            // a failed transfer paid from a bank card goes back to that card, not into the wallet
            if(!str(t,"cardChargeId").isBlank()&&!yes(t,"returnedToCard")&&openBanking.refundCharge(uid,str(t,"cardConsentRef"),str(t,"cardChargeId"),str(t,"cardAccountId"),a+fee)){
                l.post("BANK_CARD_REFUND","Bank kartına qaytarma","wallet:"+uid,"CUSTOMER_FUNDS_BANK",a+fee);t.put("returnedToCard",true);
            }
        }
        t.put("status",approved?"COMPLETED":"FAILED");s.save("CARD_TRANSFER",t);notify(t,approved?"Karta köçürmə çatdı":"Köçürmə alınmadı · rezerv açıldı");s.audit("SYSTEM","CARD_TRANSFER_"+t.get("status"),publicTransfer(t));
    }
    @Scheduled(fixedDelay=500) @Transactional public void process(){
        s.lock();
        for(var t:s.list("CARD_TRANSFER",null)){
            String status=str(t,"status");
            if(status.equals("SCHEDULED")&&num(t,"executeAt")<=System.currentTimeMillis()){t.put("status","HELD");s.save("CARD_TRANSFER",t);status="HELD";}
            if(!Set.of("HELD","SENT","UNDER_REVIEW").contains(status))continue;
            if(t.containsKey("nextPollAt")&&num(t,"nextPollAt")>System.currentTimeMillis())continue;
            String decision="UNKNOWN";
            try{
                if(!yes(t,"submitted")){sim.call("/oct/submit",m("transferId",t.get("id"),"cardToken",t.get("cardToken"),"amountMinor",t.get("amountMinor")));t.put("submitted",true);if(!status.equals("UNDER_REVIEW"))t.put("status","SENT");}
                decision=str(sim.call("/oct/status",m("transferId",t.get("id"))),"status");
                t.put("nextPollAt",System.currentTimeMillis()+1000);
            }catch(Problem error){t.put("nextPollAt",System.currentTimeMillis()+3000);}
            s.save("CARD_TRANSFER",t);
            if(decision.equals("APPROVED")){finish(t,true);continue;}
            if(decision.equals("DECLINED")){finish(t,false);continue;}
            if(num(t,"deadlineAt")<=System.currentTimeMillis()&&!str(t,"status").equals("UNDER_REVIEW")){
                t.put("status","UNDER_REVIEW");s.save("CARD_TRANSFER",t);notify(t,"Kart köçürməsi yoxlanılır");
            }
        }
    }
}
