package az.kampus;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import static az.kampus.Store.*;
@Component public class Seed implements ApplicationRunner {
 @org.springframework.beans.factory.annotation.Value("${kampus.demo-pin}") String demoPin;
 @org.springframework.beans.factory.annotation.Value("${kampus.admin-pin}") String adminPin;
 final Store s;final Ledger l;final Auth a;final Tokens tokens;
 Seed(Store s,Ledger l,Auth a,Tokens tokens){this.s=s;this.l=l;this.a=a;this.tokens=tokens;}
 @Override @Transactional public void run(ApplicationArguments args){s.lock();s.db.update("UPDATE idempotency SET response=? WHERE fingerprint LIKE ?",s.encode(m("sensitive",true)),"cards/%/reveal|%");for(var kyc:s.list("KYC",null)){kyc.put("status","DEMO_VERIFIED");kyc.put("liveness","DEMO_SIMULATED");kyc.put("mediaStored",false);kyc.remove("artifactCipher");kyc.remove("livenessPassed");tokens.protect(kyc);s.save("KYC",kyc);}if(s.db.queryForObject("SELECT COUNT(*) FROM users",Long.class)>0)return;
  for(String acc:new String[]{"CUSTOMER_FUNDS_BANK","OWN_FUNDS_BANK","CREDIT_RECEIVABLE","CARD_SETTLEMENT","ANIPAY_SETTLEMENT","VASP_SETTLEMENT","LENDER_SETTLEMENT"})l.account(acc,"D");
  l.account("FEE_INCOME","C");l.account("CAPITAL","C");l.post("CAPITAL","Demo öz kapitalı","OWN_FUNDS_BANK","CAPITAL",75_000_000);
  String[] names={"Aytən Məmmədova","Murad Əliyev","Leyla Həsənova","Kamran Quliyev","Kampus Admin","Uyğunluq əməkdaşı","Kafe Xazar"};
  String hash=a.encodePin(demoPin),adminHash=a.encodePin(adminPin);
  for(int i=0;i<names.length;i++){String id="u"+(i+1),role=i==4?"ADMIN":i==5?"COMPLIANCE":i==6?"MERCHANT":"USER";
   s.db.update("INSERT INTO users(id,phone,name,pin_hash,role,device_id,birth_date,created_at) VALUES(?,?,?,?,?,?,?,?)",id,"+99450100000"+(i+1),names[i],i>=4?adminHash:hash,role,"demo-device","2003-03-15","2026-01-01T00:00:00Z");
   if(role.equals("USER")){l.account("wallet:"+id,"C");l.post("TOPUP","Demo başlanğıc balansı","CUSTOMER_FUNDS_BANK","wallet:"+id,i==0?52_480:25_000);long initial=i==0?52_480:25_000;for(int n=0;n<3;n++){long a=n==2?initial-initial/4*2:initial/4;s.add("TX",id,m("type","TOPUP","name","Demo başlanğıc daxilolması","amountMinor",a,"direction","IN","status","COMPLETED","category","Daxilolmalar","createdAt",java.time.Instant.now().minus(java.time.Duration.ofDays(n==0?65:n==1?20:10)).toString()));}}
  }
  l.account("merchant:m1","C");s.add("MERCHANT","u7",m("id","m1","name","Kafe Xazar","mcc","5812","feeBps",50));
  s.add("SETTING","SYSTEM",m("id","crypto.enabled","value",false));s.add("SETTING","SYSTEM",m("id","total_capital_minor","value",75_000_000));
  s.add("CARD","u1",m("id","card-demo","type","MERCHANT_LOCKED","last4","2048","merchantName","Cursor","monthlyLimitMinor",3400,"spentMinor",0,"status","ACTIVE","name","Cursor abunəliyi","ecommerceEnabled",true,"atmEnabled",false,"abroadEnabled",false,"blockedMerchants",java.util.List.of()));
  s.add("KYC","u1",tokens.protectedRecord(m("userId","u1","phone","+994501000001","name","Aytən Məmmədova","fin","7AZ1234","birthDate","2003-03-15","faceMatchScore",0.96,"liveness","DEMO_SIMULATED","livenessActions",java.util.List.of("TURN_LEFT","BLINK","SMILE"),"simaContractId","sima-seed-u1","status","DEMO_VERIFIED")));
  s.add("NOTIFICATION","u1",m("title","Kampusa xoş gəldin!","body","Demo hesabında bütün əməliyyatlar test vəsaitləri ilə aparılır.","read",false));
 }
}
