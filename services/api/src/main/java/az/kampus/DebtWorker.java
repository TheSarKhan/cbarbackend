package az.kampus;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static az.kampus.Store.*;

@Service
public class DebtWorker {
 final Store s;final Ledger ledger;final Engine engine;
 DebtWorker(Store s,Ledger ledger,Engine engine){this.s=s;this.ledger=ledger;this.engine=engine;}
 static final Set<String> OPEN=Set.of("ACTIVE","OVERDUE","BAD");
 String owner(Map<String,Object> row){return s.db.queryForObject("SELECT owner_id FROM records WHERE id=?",String.class,row.get("id"));}
 void repayBnpl(String uid,Map<String,Object> loan,boolean automatic){
  Problem.require(OPEN.contains(str(loan,"status")),"LOAN_CLOSED","Borc artıq ödənilib.");
  long a=num(loan,"outstandingMinor");Problem.require(ledger.available("wallet:"+uid)>=a,"INSUFFICIENT_FUNDS","Balans kifayət etmir.");
  ledger.post("BNPL_REPAY","Sonra ödə qaytarma","wallet:"+uid,"CREDIT_RECEIVABLE",a);
  ledger.post("BNPL_OWN_RETURN","Öz vəsaitin qaytarılması","OWN_FUNDS_BANK","CUSTOMER_FUNDS_BANK",a);
  loan.put("outstandingMinor",0);loan.put("status","REPAID");loan.put("paidAt",now());loan.put("automatic",automatic);s.save("BNPL",loan);
  engine.tx(uid,"BNPL_REPAY","Sonra ödə · qaytarıldı",a,"OUT","Kredit");engine.notify(uid,"Sonra ödə borcu qaytarıldı",automatic?"Ödəniş tarixində balansdan avtomatik tutuldu.":"Balansdan ödənildi.");
 }
 void repayPartner(String uid,Map<String,Object> loan,Map<String,Object> inst,boolean automatic){
  Problem.require(Set.of("SCHEDULED","OVERDUE").contains(str(inst,"status")),"INSTALLMENT_UNAVAILABLE","Ödəniş artıq tamamlanıb.");
  long a=num(inst,"amountMinor");Problem.require(ledger.available("wallet:"+uid)>=a,"INSUFFICIENT_FUNDS","Balans kifayət etmir.");
  ledger.post("PARTNER_REPAY","Partnyor kredit ödənişi","wallet:"+uid,"LENDER_SETTLEMENT",a);engine.payout("LENDER_SETTLEMENT",a);
  inst.put("status","PAID");inst.put("paidAt",now());inst.put("automatic",automatic);s.save("INSTALLMENT",inst);
  loan.put("outstandingMinor",num(loan,"outstandingMinor")-a);
  boolean overdue=s.list("INSTALLMENT",uid).stream().anyMatch(x->str(loan,"id").equals(str(x,"loanId"))&&str(x,"status").equals("OVERDUE"));
  loan.put("status",num(loan,"outstandingMinor")==0?"REPAID":overdue?"OVERDUE":"ACTIVE");s.save("PARTNER_LOAN",loan);
  engine.tx(uid,"CREDIT_REPAY",str(loan,"partner")+" · aylıq ödəniş",a,"OUT","Kredit");engine.notify(uid,"Kredit ödənişi tamamlandı",automatic?"Aylıq ödəniş balansdan avtomatik tutuldu.":"Aylıq ödəniş qəbul edildi.");
 }
 @Scheduled(fixedDelayString="${kampus.debt-delay-ms:30000}") @Transactional
 public void process(){processAt(Instant.now());}
 void processAt(Instant at){
  s.lock();
  for(var loan:s.list("BNPL",null))if(OPEN.contains(str(loan,"status"))){
   Instant due=Instant.parse(str(loan,"dueAt"));if(due.isAfter(at))continue;
   String uid=owner(loan);long a=num(loan,"outstandingMinor");
   if(!Boolean.FALSE.equals(loan.get("autoPay"))&&ledger.available("wallet:"+uid)>=a){repayBnpl(uid,loan,true);continue;}
   String status=due.plus(Duration.ofDays(60)).isBefore(at)?"BAD":"OVERDUE";
   if(!status.equals(str(loan,"status"))){loan.put("status",status);s.save("BNPL",loan);engine.notify(uid,"Sonra ödə gecikməsi","Balans kifayət etmir. Borcu ödəmək üçün balansı artırın.");}
  }
  String today=at.atOffset(ZoneOffset.UTC).toLocalDate().toString();
  var installments=new ArrayList<>(s.list("INSTALLMENT",null));installments.sort(Comparator.comparing(x->str(x,"dueDate")));
  for(var inst:installments)if(Set.of("SCHEDULED","OVERDUE").contains(str(inst,"status"))&&str(inst,"dueDate").compareTo(today)<=0){
   String uid=owner(inst);var loan=s.own("PARTNER_LOAN",str(inst,"loanId"),uid);
   if(!Boolean.FALSE.equals(loan.get("autoPay"))&&ledger.available("wallet:"+uid)>=num(inst,"amountMinor")){repayPartner(uid,loan,inst,true);continue;}
   if(!str(inst,"status").equals("OVERDUE")){inst.put("status","OVERDUE");s.save("INSTALLMENT",inst);loan.put("status","OVERDUE");s.save("PARTNER_LOAN",loan);engine.notify(uid,"Kredit ödənişi gecikir","Aylıq ödəniş üçün balans kifayət etmir.");}
  }
  s.db.update("DELETE FROM sessions WHERE expires_at<?",at.toEpochMilli());
 }
}
