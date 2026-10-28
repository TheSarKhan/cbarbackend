package az.kampus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;
import java.time.*;
import java.math.*;
import static az.kampus.Store.*;
@Service public class Engine {
 @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy DebtWorker debts;
 final Store s;final Ledger l;final Auth auth;final Simulator sim;final CardTransfers cardTransfers;final CreditPolicy creditPolicy;final OpenBanking openBanking;
 final Tokens tokens;final InsightsService insights;
 Engine(Store s,Ledger l,Auth auth,Simulator sim,CardTransfers cardTransfers,CreditPolicy creditPolicy,OpenBanking openBanking,Tokens tokens,InsightsService insights){this.insights=insights;this.tokens=tokens;this.s=s;this.l=l;this.auth=auth;this.sim=sim;this.cardTransfers=cardTransfers;this.creditPolicy=creditPolicy;this.openBanking=openBanking;}
 void notify(String user,String title,String body){s.add("NOTIFICATION",user,m("title",title,"body",body,"read",false));}
 Map<String,Object> tx(String owner,String type,String name,long amount,String direction,String category){
  String month=LocalDate.now(ZoneOffset.UTC).toString().substring(0,7);
  long before=direction.equals("OUT")?s.list("TX",owner).stream().filter(x->!yes(x,"demoHistory")&&str(x,"status").equals("COMPLETED")&&str(x,"direction").equals("OUT")&&str(x,"createdAt").startsWith(month)&&category.equals(str(x,"category"))).mapToLong(x->num(x,"amountMinor")).sum():0;
  var row=s.add("TX",owner,m("type",type,"name",name,"amountMinor",amount,"direction",direction,"category",category,"status","COMPLETED"));
  if(direction.equals("OUT")){
   for(var bud:s.list("BUDGET",owner))if(category.equals(str(bud,"category"))){
    long lim=num(bud,"limitMinor"),after=before+amount,warn=(lim*80)/100;
    if(lim>0&&before<lim&&after>=lim)notify(owner,"Büdcə limiti aşılıb (100%)",category+" üzrə aylıq büdcə limiti keçildi.");
    else if(lim>0&&before<warn&&after>=warn)notify(owner,"Büdcə xəbərdarlığı (80%)",category+" büdcəsinin 80%-dən çoxu istifadə olunub.");
   }
  }
  return row;
 }
 void payout(String debit,long amount){l.post("SETTLEMENT","Demo bank hesablaşması",debit,"CUSTOMER_FUNDS_BANK",amount);}
 Map<String,Object> resolve(String phone){
  phone=phone.trim().toUpperCase(Locale.ROOT);
  if(phone.matches("[A-Z0-9]{7}")){
   final String fin=phone;
   var matches=s.list("KYC",null).stream().filter(k->tokens.has(k,"fin","fin",fin)).toList();
   for(var k:matches){
    var people=s.db.queryForList("SELECT id,name,phone,avatar FROM users WHERE id=? AND role='USER'",str(k,"userId"));
    if(!people.isEmpty()){var person=people.getFirst();return m("channel","INTERNAL","recipientId",person.get("id"),"maskedName",person.get("name"),"phone",phone,"avatar",person.get("avatar"),"identifierType","FIN","recipientPhone",person.get("phone"));}
   }
  }
  var u=s.db.queryForList("SELECT id,name,avatar FROM users WHERE phone=? AND role='USER'",phone);
  if(!u.isEmpty()){var r=u.getFirst();return m("channel","INTERNAL","recipientId",r.get("id"),"maskedName",r.get("name"),"phone",phone,"avatar",r.get("avatar"),"recipientPhone",phone);}
  if(phone.matches("\\+99455[0-9]{7}"))return m("channel","ANIPAY","recipientId","ANIPAY_SETTLEMENT","maskedName","Demo alıcı","bankName","Mock Bank","phone",phone);
  // AniPay also accepts the recipient's FIN code; the identifier travels in the same "phone" field so tokens, risk rules and trusted beneficiaries work unchanged
  if(phone.matches("[A-Z0-9]{7}"))return m("channel","ANIPAY","recipientId","ANIPAY_SETTLEMENT","maskedName","Demo alıcı","bankName","Mock Bank","phone",phone,"fin",phone.substring(0,2)+"***"+phone.substring(5));
  throw new Problem(404,"RECIPIENT_NOT_FOUND","Nömrə və ya FİN kod Kampus və AniPay kataloqunda yoxdur.");
 }
 /**
  * Closes a student account so the same phone number, FİN and ID series can register again (handy for testing and for a real "delete my data" request).
  * The demo accounts stay. Money left on the wallet is moved to a closed-accounts balance so the books still add up; ledger history is kept.
  */
 Map<String,Object> deleteAccount(Auth.User u,Map<String,Object> b,String proof){
  Problem.require("USER".equals(u.role()),"NOT_ALLOWED","Yalnız tələbə hesabı silinə bilər.");
  Problem.require(!u.phone().startsWith("+99450100000"),"DEMO_ACCOUNT","Demo hesabları silmək olmaz.");
  auth.requireSca(u,proof,"account/delete",b);
  String uid=u.id(),wallet="wallet:"+uid;
  Problem.require(l.held(wallet)==0,"PENDING_OPERATION","Gözləyən əməliyyat var. Bir neçə saniyə sonra yenidən sınayın.");
  long balance=l.balance(wallet);
  if(balance>0){l.account("CLOSED_ACCOUNT_FUNDS","C");l.post("ACCOUNT_CLOSED","Hesab bağlandı",wallet,"CLOSED_ACCOUNT_FUNDS",balance);}
  s.audit(uid,"ACCOUNT_DELETED",m("phone",u.phone(),"balanceMinor",balance));
  s.db.update("DELETE FROM sessions WHERE user_id=?",uid);
  s.db.update("DELETE FROM payment_tokens WHERE user_id=?",uid);
  s.db.update("DELETE FROM kyc_media WHERE user_id=?",uid);
  s.db.update("DELETE FROM idempotency WHERE user_id=?",uid);
  s.db.update("DELETE FROM records WHERE owner_id=? OR (kind='OTP' AND owner_id=?)",uid,u.phone());
  s.db.update("DELETE FROM users WHERE id=?",uid);
  return m("deleted",true);
 }
 /** The sender's note travels with the transaction, so the recipient sees it too. */
 void withNote(Map<String,Object> row,String note){if(!note.isBlank()){row.put("note",note);s.save("TX",row);}}
 boolean isBlacklisted(String phone){return s.list("BLACKLIST",null).stream().anyMatch(x->phone.equals(str(x,"target")));}
 Map<String,Object> risk(Auth.User u,Map<String,Object>b){long a=amount(b);int score=0;var rules=new ArrayList<String>();String note=str(b,"note").toLowerCase(Locale.forLanguageTag("az")),phone=str(b,"phone");
  if(s.list("BLACKLIST",null).stream().anyMatch(x->phone.equals(str(x,"target")))){score+=100;rules.add("BLACKLIST");}
  if(note.matches(".*(uduş|depozit|kredit|bonus|investisiya).*")){score+=30;rules.add("SCAM_KEYWORDS");}
  if(a>20000){score+=35;rules.add("NEW_PAYEE");}
  String device=s.db.queryForObject("SELECT device_id FROM users WHERE id=?",String.class,u.id());if(!u.device().equals(device)&&a>20000){score+=50;rules.add("NEW_DEVICE");}
  if(s.list("TRANSFER",u.id()).stream().filter(t->num(t,"executeAt")>System.currentTimeMillis()-600_000).count()>=5){score+=40;rules.add("VELOCITY");}
  if(s.list("TRANSFER",null).stream().filter(t->phone.equals(str(t,"phone"))&&num(t,"executeAt")>System.currentTimeMillis()-86_400_000).map(t->str(t,"senderId")).distinct().count()>=5){score+=45;rules.add("MULE_RECIPIENT");}
  if(yes(b,"nightTime")&&a>30000){score+=25;rules.add("NIGHT_LARGE");}
  if(!str(b,"geoCountry").isBlank()&&!str(b,"geoCountry").equals("AZ")){score+=35;rules.add("FOREIGN_GEO");}
  int r=Math.min(score,100);return m("score",r,"rules",rules,"action",rules.contains("BLACKLIST")?"BLOCK":r>=80?"HOLD_FOR_REVIEW":r>=60?"WARN_AND_SCA":r>=30?"REQUIRE_SCA":"ALLOW");
 }
 void scaIf(Auth.User u,String proof,String action,Map<String,Object>b,boolean required){long a=amount(b);var counter=s.list("EXEMPT_COUNTER",u.id());long count=counter.isEmpty()?0:num(counter.getFirst(),"count"),sum=counter.isEmpty()?0:num(counter.getFirst(),"sumMinor");if(required||count>=5||sum+a>30000)auth.requireSca(u,proof,action,b);else {var state=counter.isEmpty()?s.add("EXEMPT_COUNTER",u.id(),m("count",0,"sumMinor",0)):counter.getFirst();state.put("count",count+1);state.put("sumMinor",sum+a);s.save("EXEMPT_COUNTER",state);}}
 @Transactional public Object write(Auth.User u,String path,Map<String,Object>b,String key,String proof){
  s.lock();Problem.require(key!=null&&key.matches("[A-Za-z0-9_-]{8,128}"),"IDEMPOTENCY_REQUIRED","Unikal Idempotency-Key tələb olunur.");
  String fingerprint=path+"|"+s.encode(b);var prev=s.db.queryForList("SELECT fingerprint,response FROM idempotency WHERE user_id=? AND idem_key=?",u.id(),key);
  if(!prev.isEmpty()){Problem.require(fingerprint.equals(prev.getFirst().get("fingerprint")),"IDEMPOTENCY_CONFLICT","Bu açar başqa sorğuda istifadə olunub.");if(path.matches("cards/[^/]+/reveal"))throw new Problem(410,"SENSITIVE_REPLAY_EXPIRED","Kart məlumatlarını yeni PIN təsdiqi ilə yenidən açın.");return s.decode(prev.getFirst().get("response").toString());}
  Object result;Simulator.operation.set(Auth.sha256(u.id()+"|"+key));try{result=execute(u,path,b,proof);}finally{Simulator.operation.remove();}s.db.update("INSERT INTO idempotency VALUES(?,?,?,?)",u.id(),key,fingerprint,s.encode(path.matches("cards/[^/]+/reveal")?m("sensitive",true):result));s.audit(u.id(),path,b);return result;
 }
 Object execute(Auth.User u,String p,Map<String,Object>b,String proof){
  String uid=u.id();String[] parts=p.split("/");
  if(p.startsWith("admin/"))return admin(u,p,b);
  if(p.startsWith("merchant/"))return merchant(u,p,b);
  auth.role(u,"USER");
  if(p.startsWith("card-transfers")||p.startsWith("recipient-cards"))return cardTransfers.write(u,p,b,proof);
  if(p.startsWith("open-banking/"))return openBanking.write(u,p,b,this::notify);
  if(p.equals("analytics/consent"))return insights.setConsent(uid,yes(b,"enabled"));
  if(p.equals("analytics/budgets/apply"))return insights.applyBudgets(uid,b.get("categories") instanceof List<?> list?list:List.of());
  if(p.equals("topups")){long a=amount(b);Problem.require(l.balance("wallet:"+uid)+a<=200_000,"WALLET_LIMIT","Demo hesabının balans limiti 2 000 AZN-dir.");Problem.require(Set.of("CARD","OPEN_BANKING","TERMINAL","BANK_CARD").contains(str(b,"source")),"INVALID_SOURCE","Doldurma üsulunu seçin.");String source=str(b,"source"),sourceLabel=Map.of("CARD","Bank kartı","OPEN_BANKING","Bağlı kart","TERMINAL","Terminal").getOrDefault(source,source);
   if(source.equals("BANK_CARD")){scaIf(u,proof,p,b,true);sourceLabel=str(openBanking.charge(uid,str(b,"accountId"),a,"Balans artırma"),"cardLabel");}else sim.call("/bank/topup",m("amountMinor",a,"source",b.get("source")));
   l.post("TOPUP","Demo balans artırma","CUSTOMER_FUNDS_BANK","wallet:"+uid,a);notify(uid,"Balans artırıldı",String.format(Locale.ROOT,"%.2f AZN",a/100.0));return tx(uid,"TOPUP","Balans artırma · "+sourceLabel,a,"IN","Daxilolmalar");}
  if(p.equals("topups/terminal-code")){long a=amount(b);Problem.require(l.balance("wallet:"+uid)+a<=200_000,"WALLET_LIMIT","Demo hesabının balans limiti 2 000 AZN-dir.");String code=String.format("%08d",new java.security.SecureRandom().nextInt(100_000_000));return s.add("TERMINAL_CODE",uid,m("code",code,"amountMinor",a,"status","ACTIVE","expiresAt",System.currentTimeMillis()+900_000));}
  if(p.equals("topups/terminal-redeem")){String code=str(b,"code");var tc=s.list("TERMINAL_CODE",uid).stream().filter(x->code.equals(str(x,"code"))).findFirst().orElseThrow(()->new Problem(404,"CODE_NOT_FOUND","Terminal kodu tapılmadı."));Problem.require(str(tc,"status").equals("ACTIVE")&&num(tc,"expiresAt")>System.currentTimeMillis(),"CODE_EXPIRED","Terminal kodunun vaxtı bitib və ya istifadə olunub.");long a=num(tc,"amountMinor");Problem.require(l.balance("wallet:"+uid)+a<=200_000,"WALLET_LIMIT","Demo hesabının balans limiti 2 000 AZN-dir.");sim.call("/bank/topup",m("amountMinor",a,"source","TERMINAL","terminalCode",code));l.post("TOPUP","Terminal kodu ilə balans artırma","CUSTOMER_FUNDS_BANK","wallet:"+uid,a);tc.put("status","USED");s.save("TERMINAL_CODE",tc);notify(uid,"Terminal ödənişi qəbul edildi",String.format(Locale.ROOT,"%.2f AZN · Kod %s",a/100.0,code));return tx(uid,"TOPUP","Terminal · "+code,a,"IN","Daxilolmalar");}
  if(p.equals("account/delete"))return deleteAccount(u,b,proof);
  if(p.equals("trusted-beneficiaries")){var to=resolve(str(b,"phone"));Problem.require(!uid.equals(to.get("recipientId")),"SELF_TRANSFER","Özünüzü əlavə edə bilməzsiniz.");auth.requireSca(u,proof,p,b);for(var old:s.list("TRUSTED_BENEFICIARY",uid))if(str(b,"phone").equals(str(old,"phone")))return old;return s.add("TRUSTED_BENEFICIARY",uid,m("phone",b.get("phone"),"recipientId",to.get("recipientId"),"maskedName",to.get("maskedName"),"channel",to.get("channel"),"nickname",str(b,"nickname").isBlank()?to.get("maskedName"):str(b,"nickname"),"avatar",to.get("avatar")));}
  if(p.matches("trusted-beneficiaries/[^/]+/delete")){var tb=s.own("TRUSTED_BENEFICIARY",parts[1],uid);s.db.update("DELETE FROM records WHERE kind='TRUSTED_BENEFICIARY' AND id=?",tb.get("id"));return m("deleted",true,"id",tb.get("id"));}
  if(p.equals("contacts/match")){
   Problem.require(!b.containsKey("phones"),"HASHED_CONTACTS_REQUIRED","Kontaktları SHA-256 heşləri ilə göndərin.");
   Object raw=b.containsKey("phoneHashes")?b.get("phoneHashes"):b.get("hashes");
   Problem.require(raw instanceof List<?>,"HASHED_CONTACTS_REQUIRED","phoneHashes siyahısı tələb olunur.");
   var hashes=(List<?>)raw;Problem.require(hashes.size()<=1000,"CONTACT_LIMIT","Maksimum 1 000 kontakt göndərin.");
   var requested=new HashSet<String>();
   for(Object hash:hashes){String h=String.valueOf(hash).toLowerCase(Locale.ROOT);Problem.require(h.matches("[0-9a-f]{64}"),"INVALID_CONTACT_HASH","SHA-256 heşi yanlışdır.");requested.add(h);}
   var matches=new ArrayList<Map<String,Object>>();
   if(!requested.isEmpty())for(var r:s.db.queryForList("SELECT id,name,phone,avatar FROM users WHERE role='USER' AND id<>?",uid)){
    String ph=String.valueOf(r.get("phone")),h=Auth.sha256(ph);
    if(requested.contains(h))matches.add(m("userId",r.get("id"),"maskedName",r.get("name"),"phone",ph,"phoneHash",h,"avatar",r.get("avatar")));
   }
   return m("matches",matches);
  }
  if(p.equals("transfers")){
   long a=amount(b);Problem.require(a<=100_000,"TRANSFER_LIMIT","Bir köçürmə maksimum 1 000 AZN-dir.");var to=resolve(str(b,"phone"));Problem.require(!uid.equals(to.get("recipientId")),"SELF_TRANSFER","Özünüzə göndərə bilməzsiniz.");
   long daily=s.list("TRANSFER",uid).stream().filter(t->str(t,"createdAt").startsWith(LocalDate.now(ZoneOffset.UTC).toString())&&!Set.of("CANCELLED","REJECTED").contains(str(t,"status"))).mapToLong(t->num(t,"amountMinor")).sum();Problem.require(daily+a<=200_000,"DAILY_LIMIT","Gündəlik köçürmə limiti aşılıb.");
   boolean trusted=s.list("TRUSTED_BENEFICIARY",uid).stream().anyMatch(x->str(b,"phone").equals(str(x,"phone")));
   var riskBody=new LinkedHashMap<String,Object>(b);riskBody.put("phone",to.getOrDefault("recipientPhone",str(b,"phone")));var risk=risk(u,riskBody);if(str(risk,"action").equals("BLOCK"))throw new Problem(403,"BLACKLISTED_RECIPIENT","Bu alana köçürmə bloklanıb.");boolean review=num(risk,"score")>=80;
   // Large amounts are confirmed by the customer (PIN and face check) even when the risk score sends them to manual review afterwards.
    boolean fromCard=!str(b,"fundingAccountId").isBlank();
    if(fromCard||a>=auth.kycLimit()||(!review&&!(trusted&&num(risk,"score")<30)))scaIf(u,proof,p,b,fromCard||a>6000||a>=auth.kycLimit()||num(risk,"score")>=30);
   // the recipient token is spent only after the confirmation passed, so a PIN prompt does not use it up
   tokens.consume(uid,str(b,"recipientToken"),"P2P_RECIPIENT",m("phone",str(b,"phone")));
   String fundedBy="";Map<String,Object> charge=m();
   if(fromCard){charge=openBanking.charge(uid,str(b,"fundingAccountId"),a,"Köçürmə");fundedBy=str(charge,"cardLabel");l.post("BANK_CARD_FUNDING","Bank kartından maliyyələşmə","CUSTOMER_FUNDS_BANK","wallet:"+uid,a);}
   String hold=l.hold("wallet:"+uid,a);var t=s.add("TRANSFER",uid,m("senderId",uid,"recipientId",to.get("recipientId"),"recipientName",to.get("maskedName"),"channel",to.get("channel"),"phone",b.get("phone"),"recipientPhone",to.getOrDefault("recipientPhone",str(b,"phone")),"amountMinor",a,"note",str(b,"note"),"trustedBeneficiary",trusted,"fundedBy",fundedBy,"cardChargeId",charge.getOrDefault("chargeId",""),"cardConsentRef",charge.getOrDefault("simConsentId",""),"cardAccountId",charge.getOrDefault("accountId",""),"status",review?"UNDER_REVIEW":"SCHEDULED","holdId",hold,"executeAt",System.currentTimeMillis()+5000,"risk",risk));
   if(review)s.add("FRAUD","SYSTEM",m("userId",uid,"transferId",t.get("id"),"amountMinor",a,"recipientName",to.get("maskedName"),"score",risk.get("score"),"rules",risk.get("rules"),"status","OPEN","expiresAt",System.currentTimeMillis()+1_800_000));
   return publicTransfer(t);
  }
  if(p.matches("transfers/[^/]+/cancel")){var t=s.own("TRANSFER",parts[1],uid);Problem.require(str(t,"status").equals("SCHEDULED")&&num(t,"executeAt")>System.currentTimeMillis(),"CANNOT_CANCEL","Ləğv etmə müddəti bitib.");l.release(str(t,"holdId"));returnToCard(t);t.put("status","CANCELLED");s.save("TRANSFER",t);return publicTransfer(t);}
  if(p.equals("payments/qr/parse")){var qr=new LinkedHashMap<>(parseQr(str(b,"qrPayload")));qr.put("paymentToken",tokens.issue(uid,"QR_PAY",qrBinding(qr),Tokens.TTL_QR_SECONDS));return qr;}
  if(p.equals("payments/qr"))return qrPay(u,b,proof);
  if(p.equals("loan-applications")){
   long a=amount(b),term=num(b,"termMonths");Problem.require(a>=5000&&a<=150000&&term>=3&&term<=24,"LOAN_LIMIT","Məbləğ 50–1 500 AZN, müddət 3–24 ay olmalıdır.");Problem.require(yes(b,"consent"),"CONSENT_REQUIRED","Kredit partnyorları ilə məlumat paylaşılmasına razılıq verin.");
   var consent=s.add("CONSENT",uid,m("type","CREDIT_DATA_SHARING","partners",List.of("Mock BOKT A","Mock Bank B"),"version","1"));var application=s.add("APPLICATION",uid,m("amountMinor",a,"termMonths",term,"purpose",str(b,"purpose"),"applicantName",u.name(),"userId",uid,"consentId",consent.get("id"),"status","OFFERS_READY"));
   var scoring=creditPolicy.scoring(uid);application.put("scoring",scoring);s.save("APPLICATION",application);var request=new LinkedHashMap<>(scoring);request.put("amountMinor",a);request.put("termMonths",term);var response=sim.call("/lenders/applications",request);@SuppressWarnings("unchecked") var offers=(List<Map<String,Object>>)response.get("offers");
   for(var o:offers){var data=new LinkedHashMap<>(o);data.put("applicationId",application.get("id"));data.put("applicantName",u.name());data.put("amountMinor",a);data.put("termMonths",term);data.put("expiresAt",System.currentTimeMillis()+86_400_000);data.put("status",yes(o,"approved")?"AVAILABLE":"DECLINED");s.add("OFFER",uid,data);}
   return application;
  }
  if(p.matches("loan-offers/[^/]+/accept")){
   var o=s.own("OFFER",parts[1],uid);Problem.require(str(o,"status").equals("AVAILABLE")&&num(o,"expiresAt")>System.currentTimeMillis(),"OFFER_UNAVAILABLE","Təklif aktiv deyil.");auth.requireSca(u,proof,p,b);long a=num(o,"amountMinor");Problem.require(l.balance("wallet:"+uid)+a<=200_000,"WALLET_LIMIT","Balans limiti aşılar.");sim.call("/lenders/disburse",m("offerId",o.get("id"),"amountMinor",a));
   l.post("LOAN_DISBURSE","Demo partnyor krediti","CUSTOMER_FUNDS_BANK","wallet:"+uid,a);
   long refFee=(a*150+5000)/10000;if(refFee>0)l.post("REFERRAL_FEE","Partnyor kredit komissiyası","OWN_FUNDS_BANK","FEE_INCOME",refFee);
   o.put("status","ACCEPTED");s.save("OFFER",o);
   String app=str(o,"applicationId");for(var other:s.list("OFFER",uid))if(app.equals(str(other,"applicationId"))&&str(other,"status").equals("AVAILABLE")){other.put("status","WITHDRAWN");s.save("OFFER",other);}
   var loan=s.add("PARTNER_LOAN",uid,m("offerId",o.get("id"),"partner",o.get("partner"),"amountMinor",a,"monthlyPaymentMinor",o.get("monthlyPaymentMinor"),"termMonths",o.get("termMonths"),"outstandingMinor",o.get("totalPayableMinor"),"referralFeeMinor",refFee,"autoPay",true,"status","ACTIVE"));
   int months=(int)num(o,"termMonths");for(int month=1;month<=months;month++)s.add("INSTALLMENT",uid,m("loanId",loan.get("id"),"number",month,"amountMinor",o.get("monthlyPaymentMinor"),"dueDate",LocalDate.now(ZoneOffset.UTC).plusMonths(month).toString(),"status","SCHEDULED"));
   tx(uid,"CREDIT",str(o,"partner")+" · kredit",a,"IN","Daxilolmalar");notify(uid,"Kredit balansınıza köçürüldü","Demo müqaviləsi qəbul edildi.");return loan;
  }
  if(p.matches("partner-loans/[^/]+/installments/[^/]+/pay")){var loan=s.own("PARTNER_LOAN",parts[1],uid);var inst=s.own("INSTALLMENT",parts[3],uid);Problem.require(str(inst,"loanId").equals(parts[1]),"INSTALLMENT_UNAVAILABLE","Ödəniş qrafiki uyğun deyil.");auth.requireSca(u,proof,p,b);debts.repayPartner(uid,loan,inst,false);return inst;}
  if(p.matches("bnpl/loans/[^/]+/repay")){var loan=s.own("BNPL",parts[2],uid);auth.requireSca(u,proof,p,b);debts.repayBnpl(uid,loan,false);return loan;}
  if(p.equals("splits")){
   long total=num(b,"totalMinor");Problem.require(total>0&&total<=100000,"SPLIT_LIMIT","Hesab 0–1 000 AZN olmalıdır.");Object raw=b.get("participants");Problem.require(raw instanceof List<?>,"PARTICIPANTS_REQUIRED","İştirakçıları seçin.");@SuppressWarnings("unchecked") var people=(List<Map<String,Object>>)raw;Problem.require(!people.isEmpty()&&people.size()<=10,"PARTICIPANT_LIMIT","1–10 dost seçin.");var seen=new HashSet<String>();var members=new ArrayList<Map<String,Object>>();long allocated=0;boolean custom=str(b,"mode").equals("CUSTOM");
   for(var person:people){var to=resolve(str(person,"phone"));String id=str(to,"recipientId");Problem.require(str(to,"channel").equals("INTERNAL")&&!uid.equals(id)&&seen.add(id),"INVALID_PARTICIPANT","Təkrarlanmayan başqa Kampus istifadəçiləri seçin.");long share=custom?num(person,"amountMinor"):total/(people.size()+1);Problem.require(share>0,"INVALID_SHARE","Hər pay müsbət olmalıdır.");allocated+=share;members.add(m("userId",id,"phone",person.get("phone"),"amountMinor",share));}
   Problem.require(allocated<total,"INVALID_SPLIT_TOTAL","Dostların payı cəmi hesabdan az olmalıdır; qalanı sənin payındır.");var split=s.add("SPLIT",uid,m("totalMinor",total,"organizerShareMinor",total-allocated,"note",str(b,"note"),"mode",custom?"CUSTOM":"EQUAL","participantCount",people.size()+1));
   for(var member:members){s.add("REQUEST",str(member,"userId"),m("requesterId",uid,"requesterName",u.name(),"amountMinor",member.get("amountMinor"),"splitId",split.get("id"),"note",str(b,"note"),"status","OPEN","expiresAt",System.currentTimeMillis()+604800000));notify(str(member,"userId"),"Hesabı böl",u.name()+" hesabı səninlə bölüşdü.");}return split;
  }
  if(p.equals("payment-requests")){long a=amount(b);var to=resolve(str(b,"phone"));Problem.require(str(to,"channel").equals("INTERNAL")&&!uid.equals(to.get("recipientId")),"INVALID_RECIPIENT","Başqa Kampus istifadəçisi seçin.");var r=s.add("REQUEST",str(to,"recipientId"),m("requesterId",uid,"requesterName",u.name(),"amountMinor",a,"note",str(b,"note"),"status","OPEN","expiresAt",System.currentTimeMillis()+604_800_000));notify(str(to,"recipientId"),"Pul sorğusu",u.name()+" sizdən pul istəyir.");return r;}
  if(p.matches("payment-requests/[^/]+/(pay|decline)")){var r=s.own("REQUEST",parts[1],uid);Problem.require(str(r,"status").equals("OPEN")&&num(r,"expiresAt")>System.currentTimeMillis(),"REQUEST_CLOSED","Sorğu artıq aktiv deyil.");if(parts[2].equals("pay")){auth.requireSca(u,proof,p,b);long a=num(r,"amountMinor");Problem.require(l.available("wallet:"+uid)>=a,"INSUFFICIENT_FUNDS","Balans kifayət etmir.");l.post("REQUEST_PAYMENT","Pul sorğusunun ödənişi","wallet:"+uid,"wallet:"+str(r,"requesterId"),a);tx(uid,"P2P",str(r,"requesterName"),a,"OUT","Köçürmələr");tx(str(r,"requesterId"),"P2P",u.name(),a,"IN","Daxilolmalar");}r.put("status",parts[2].equals("pay")?"PAID":"DECLINED");s.save("REQUEST",r);return r;}
  if(p.matches("notifications/[^/]+/read")){var n=s.own("NOTIFICATION",parts[1],uid);n.put("read",true);s.save("NOTIFICATION",n);return n;}
  if(p.equals("budgets")||p.startsWith("budgets/")){long limit=num(b,"limitMinor");Problem.require(limit>0,"INVALID_LIMIT","Büdcə müsbət olmalıdır.");String cat=!str(b,"category").isBlank()?str(b,"category"):java.net.URLDecoder.decode(p.substring(8),java.nio.charset.StandardCharsets.UTF_8);for(var old:s.list("BUDGET",uid))if(cat.equals(old.get("category"))){old.put("limitMinor",limit);s.save("BUDGET",old);return old;}return s.add("BUDGET",uid,m("category",cat,"limitMinor",limit));}
  if(p.matches("transactions/[^/]+/category")){String kind=parts[1].startsWith("ob_")?"OB_TX":"TX";var t=s.own(kind,parts[1],uid);String cat=str(b,"category").trim();Problem.require(!cat.isBlank(),"CATEGORY_REQUIRED","Kateqoriya seçin.");t.put("category",cat);s.save(kind,t);return t;}
  throw new Problem(404,"ENDPOINT_NOT_FOUND","Əməliyyat tapılmadı.");
 }
 Map<String,Object> parseQr(String payload){var tags=EmvQr.parse(payload);Problem.require("az.kampus".equals(EmvQr.fields(tags.getOrDefault("26","")).get("00"))&&"944".equals(tags.get("53")),"UNSUPPORTED_QR","Yalnız Kampus AZN QR kodu dəstəklənir.");String mid=EmvQr.fields(tags.get("26")).get("01");var merchant=s.get("MERCHANT",mid);String intent=EmvQr.fields(tags.getOrDefault("62","")).get("05");long amount=0;
  if(intent!=null){var i=s.get("INTENT",intent);Problem.require(str(i,"status").equals("OPEN"),"INTENT_ALREADY_PAID","QR artıq ödənilib.");Problem.require(num(i,"expiresAt")>System.currentTimeMillis(),"QR_EXPIRED","QR kodun vaxtı bitib.");Problem.require(str(i,"merchantId").equals(mid),"INVALID_QR","Satıcı uyğun gəlmir.");amount=num(i,"amountMinor");}
  return m("merchantId",mid,"merchantName",merchant.get("name"),"intentId",intent==null?"":intent,"amountMinor",amount,"qrPayload",payload);
 }
 Map<String,Object> qrBinding(Map<String,Object> qr){return m("merchantId",str(qr,"merchantId"),"qr",Auth.sha256(str(qr,"qrPayload")));}
 Map<String,Object> qrPay(Auth.User u,Map<String,Object>b,String proof){var qr=parseQr(str(b,"qrPayload"));tokens.consume(u.id(),str(b,"paymentToken"),"QR_PAY",qrBinding(qr));long a=amount(b);long fixed=num(qr,"amountMinor");Problem.require(fixed==0||fixed==a,"AMOUNT_MISMATCH","QR məbləği dəyişdirilə bilməz.");scaIf(u,proof,"payments/qr",b,a>6000||!str(b,"fundingSource").equals("WALLET"));
  String uid=u.id(),mid=str(qr,"merchantId"),source=str(b,"fundingSource");String payid=id();String debit;
  if(source.equals("BNPL")){Problem.require(yes(creditPolicy.eligibility(uid),"eligible"),"BNPL_INELIGIBLE","Sonra ödə üçün 14 günlük hesab, son 30 gündə iki daxilolma və gecikməsiz borc tələb olunur.");long debt=outstanding(uid);Problem.require(a+debt<=5000,"BNPL_LIMIT","Sonra ödə limiti 50 AZN-dir.");creditPolicy.requireNewLoan(a);Problem.require(l.balance("OWN_FUNDS_BANK")>=a,"OWN_FUNDS_LIMIT","Öz vəsait kifayət etmir.");debit="CREDIT_RECEIVABLE";l.post("BNPL_FUND","Öz vəsaitdən maliyyələşmə","CUSTOMER_FUNDS_BANK","OWN_FUNDS_BANK",a);s.add("BNPL",uid,m("paymentId",payid,"principalMinor",a,"outstandingMinor",a,"dueAt",Instant.now().plus(Duration.ofDays(30)).toString(),"autoPay",true,"status","ACTIVE"));}
  else if(source.equals("BANK_CARD")){openBanking.charge(uid,str(b,"accountId"),a,"QR · "+str(qr,"merchantName"));l.post("BANK_CARD_FUNDING","Bank kartından maliyyələşmə","CUSTOMER_FUNDS_BANK","wallet:"+uid,a);debit="wallet:"+uid;}
  else {Problem.require(source.equals("WALLET"),"INVALID_SOURCE","Ödəniş mənbəyi yanlışdır.");Problem.require(l.available("wallet:"+uid)>=a,"INSUFFICIENT_FUNDS","Balans kifayət etmir.");debit="wallet:"+uid;}
  l.post("QR_PAYMENT","Kafe Xazar",debit,"merchant:"+mid,a);long fee=(a*50+5000)/10000;if(fee>0){l.post("MERCHANT_FEE","Satıcı komissiyası","merchant:"+mid,"FEE_INCOME",fee);l.post("FEE_SWEEP","Komissiya ayrılması","OWN_FUNDS_BANK","CUSTOMER_FUNDS_BANK",fee);}
  var payment=s.add("PAYMENT",uid,m("id",payid,"merchantId",mid,"merchantName",qr.get("merchantName"),"amountMinor",a,"feeMinor",fee,"fundingSource",source,"status","COMPLETED","payerId",uid));
  if(!str(qr,"intentId").isBlank()){var i=s.get("INTENT",str(qr,"intentId"));i.put("status","PAID");i.put("paymentId",payid);s.save("INTENT",i);}
  tx(uid,"QR",str(qr,"merchantName"),a,"OUT","Yemək");notify(uid,"Ödəniş tamamlandı",str(qr,"merchantName"));return payment;
 }
 long outstanding(String uid){return CreditPolicy.outstanding(s.list("BNPL",uid));}
 Object merchant(Auth.User u,String p,Map<String,Object>b){auth.role(u,"ADMIN","MERCHANT");String mid="m1";String[] parts=p.split("/");
  if(p.equals("merchant/qr/dynamic")){long a=amount(b);var i=s.add("INTENT",u.id(),m("merchantId",mid,"amountMinor",a,"status","OPEN","expiresAt",System.currentTimeMillis()+300000));i.put("qrPayload",EmvQr.create(mid,str(i,"id"),a));s.save("INTENT",i);return i;}
  if(p.matches("merchant/payments/[^/]+/refund")){var pay=s.get("PAYMENT",parts[2]);Problem.require(str(pay,"merchantId").equals(mid)&&str(pay,"status").equals("COMPLETED"),"REFUND_UNAVAILABLE","Ödəniş geri qaytarıla bilməz.");Problem.require(Instant.parse(str(pay,"createdAt")).plus(Duration.ofDays(30)).isAfter(Instant.now()),"REFUND_EXPIRED","Geri qaytarma müddəti bitib.");
   long a=num(pay,"amountMinor"),fee=num(pay,"feeMinor");String uid=str(pay,"payerId"),source=str(pay,"fundingSource");
   if(fee>0){l.post("FEE_REVERSAL","Komissiya geri qaytarma","CUSTOMER_FUNDS_BANK","OWN_FUNDS_BANK",fee);l.post("FEE_REVERSAL","Komissiya geri qaytarma","FEE_INCOME","merchant:"+mid,fee);}
   if(source.equals("BNPL")){var loan=s.list("BNPL",uid).stream().filter(x->str(pay,"id").equals(str(x,"paymentId"))).findFirst().orElseThrow();if(Set.of("ACTIVE","OVERDUE","BAD").contains(str(loan,"status"))){l.post("REFUND","Sonra ödə ləğvi","merchant:"+mid,"CREDIT_RECEIVABLE",a);l.post("BNPL_OWN_RETURN","Öz vəsaitin qaytarılması","OWN_FUNDS_BANK","CUSTOMER_FUNDS_BANK",a);loan.put("outstandingMinor",0);loan.put("status","REFUNDED");s.save("BNPL",loan);}else l.post("REFUND","Geri qaytarma","merchant:"+mid,"wallet:"+uid,a);}
   else {l.post("REFUND","Geri qaytarma","merchant:"+mid,"wallet:"+uid,a);}
   pay.put("status","REFUNDED");s.save("PAYMENT",pay);tx(uid,"REFUND","Kafe Xazar · geri qaytarma",a,"IN","Daxilolmalar");return pay;
  }throw new Problem(404,"NOT_FOUND","Kassa əməliyyatı tapılmadı.");
 }
 Object admin(Auth.User u,String p,Map<String,Object>b){auth.role(u,"ADMIN","COMPLIANCE");String[] parts=p.split("/");
  if(p.startsWith("admin/feature-flags/")||p.startsWith("admin/system-params/")){auth.role(u,"ADMIN");String key=parts[2];Problem.require(Set.of("total_capital_minor").contains(key),"INVALID_SETTING","Parametr tapılmadı.");var setting=s.get("SETTING",key);Object value=b.get("value");Problem.require(num(b,"value")>0,"INVALID_CAPITAL","Kapital müsbət olmalıdır.");setting.put("value",value);s.save("SETTING",setting);return setting;}
  if(p.matches("admin/card-transfers/[^/]+/resolve")){auth.role(u,"ADMIN");var t=s.get("CARD_TRANSFER",parts[2]);Problem.require(str(t,"status").equals("UNDER_REVIEW"),"TRANSFER_NOT_UNDER_REVIEW","Yalnız gecikən köçürmə yoxlanıla bilər.");String decision=str(b,"decision");Problem.require(Set.of("APPROVED","DECLINED").contains(decision),"INVALID_DECISION","Cavab yanlışdır.");sim.call("/oct/demo-resolve",m("transferId",t.get("id"),"decision",decision));return m("status","AWAITING_NETWORK_CONFIRMATION","demo",true);}
  if(p.equals("admin/reconciliation/run"))return s.add("RECONCILIATION","SYSTEM",l.reconcile());
  if(p.matches("admin/fraud/cases/[^/]+/(approve|reject)")){var f=s.get("FRAUD",parts[3]);Problem.require(str(f,"status").equals("OPEN"),"CASE_CLOSED","Müraciət artıq bağlanıb.");var t=s.get("TRANSFER",str(f,"transferId"));
   if(parts[4].equals("approve")){Problem.require(!isBlacklisted(str(t,"phone")),"BLACKLISTED_RECIPIENT","Qara siyahıdakı alana köçürmə buraxıla bilməz.");t.put("status","SCHEDULED");t.put("executeAt",System.currentTimeMillis()+5000);}else {t.put("status","REJECTED");l.release(str(t,"holdId"));returnToCard(t);notify(str(t,"senderId"),"Köçürmə rədd edildi","Rezerv edilmiş vəsait açıldı.");}s.save("TRANSFER",t);f.put("status",parts[4].equals("approve")?"APPROVED":"REJECTED");s.save("FRAUD",f);return f;
  }
  if(p.equals("admin/fraud/blacklist")){String target=str(b,"target").trim();Problem.require(!target.isBlank(),"TARGET_REQUIRED","Hədəf nömrə və ya ünvan lazımdır.");return s.add("BLACKLIST",u.id(),m("target",target,"reason",str(b,"reason")));}
  throw new Problem(404,"NOT_FOUND","Admin əməliyyatı tapılmadı.");
 }
 @Transactional public Object read(Auth.User u,String p,String query){if(p.equals("me"))return auth.publicUser(u);
  if(p.startsWith("admin/")){auth.role(u,"ADMIN","COMPLIANCE");return switch(p){
   case "admin/analytics" -> insights.adminOverview();
   case "admin/overview" -> m("transactions",s.list("TX",null).stream().filter(x->!str(x,"type").equals("P2P")||str(x,"direction").equals("OUT")).toList(),"users",s.db.queryForList("SELECT id,name,phone,role FROM users"),"kycRecords",s.list("KYC",null).stream().map(k->{var c=new LinkedHashMap<>(k);c.remove("finEnc");c.remove("idSeriesEnc");return c;}).toList(),"fraudCases",s.list("FRAUD",null),"fraudMetrics",fraudMetrics(),"blacklist",s.list("BLACKLIST",null),"applications",s.list("APPLICATION",null),"offers",s.list("OFFER",null),"partnerLoans",s.list("PARTNER_LOAN",null),"cardTransfers",s.list("CARD_TRANSFER",null).stream().map(cardTransfers::publicTransfer).toList(),"settings",s.list("SETTING",null),"compliance",compliance(),"audit",s.db.queryForList("SELECT actor,action,created_at FROM audit_log ORDER BY created_at DESC LIMIT 100"),"reconciliation",l.reconcile());
   case "admin/fraud/cases" -> s.list("FRAUD",null);
   case "admin/fraud/metrics" -> fraudMetrics();
   case "admin/credit/compliance" -> compliance();
   case "admin/reconciliation/runs" -> s.list("RECONCILIATION",null);
   default -> throw new Problem(404,"NOT_FOUND","Səhifə tapılmadı.");
  };}
  if(p.startsWith("merchant/")){auth.role(u,"ADMIN","MERCHANT");return switch(p){
   case "merchant/payments" -> s.list("PAYMENT",null).stream().filter(x->str(x,"merchantId").equals("m1")).toList();
   case "merchant/qr/static" -> m("qrPayload",EmvQr.create("m1",null,0));
   case "merchant/intents" -> s.list("INTENT",null).stream().filter(x->str(x,"merchantId").equals("m1")).toList();
   default -> throw new Problem(404,"NOT_FOUND","Səhifə tapılmadı.");
  };}
  auth.role(u,"USER");String uid=u.id();
  if(p.startsWith("card-transfers")||p.startsWith("card-sessions/")||p.startsWith("recipient-cards"))return cardTransfers.read(u,p);
  if(p.startsWith("open-banking/"))return openBanking.read(u,p);
  return switch(p){
   case "wallet" -> l.wallet(uid);
   case "dashboard" -> m("user",auth.publicUser(u),"wallet",l.wallet(uid),"transactions",customerTransactions(uid),"transfers",publicTransfers(uid),"trustedBeneficiaries",s.list("TRUSTED_BENEFICIARY",uid),"terminalCodes",s.list("TERMINAL_CODE",uid),"notifications",s.list("NOTIFICATION",uid),"requests",s.list("REQUEST",uid),"bnplLoans",s.list("BNPL",uid),"partnerLoans",s.list("PARTNER_LOAN",uid),"applications",s.list("APPLICATION",uid),"offers",s.list("OFFER",uid),"bnplEligibility",creditPolicy.eligibility(uid),"bnplAvailableMinor",creditPolicy.eligibility(uid).get("availableMinor"),"analytics",analytics(uid),"insightsBrief",insights.brief(uid),"openBanking",openBanking.overview(uid),"budgets",s.list("BUDGET",uid),"splits",s.list("SPLIT",uid),"cardTransfers",s.list("CARD_TRANSFER",uid).stream().map(cardTransfers::publicTransfer).toList(),"recipientCards",cardTransfers.read(u,"recipient-cards"));
   case "transactions" -> customerTransactions(uid);
   case "trusted-beneficiaries" -> s.list("TRUSTED_BENEFICIARY",uid);
   case "notifications" -> s.list("NOTIFICATION",uid);
   case "transfers/resolve" -> {var r=new LinkedHashMap<>(resolve(query));r.remove("recipientPhone");r.put("recipientToken",tokens.issue(uid,"P2P_RECIPIENT",m("phone",query),300));yield r;}
   case "bnpl/eligibility" -> creditPolicy.eligibility(uid);
   case "bnpl/loans" -> s.list("BNPL",uid);
   case "payment-requests" -> s.list("REQUEST",uid);
   case "budgets" -> s.list("BUDGET",uid);
   case "analytics/summary" -> analytics(uid);
   case "analytics/insights" -> insights.view(u,this::notify);
   default -> {String[] parts=p.split("/");if(p.matches("loan-applications/[^/]+/offers")){s.own("APPLICATION",parts[1],uid);yield s.list("OFFER",uid).stream().filter(o->str(o,"applicationId").equals(parts[1])).toList();}if(p.matches("partner-loans/[^/]+/schedule")){s.own("PARTNER_LOAN",parts[1],uid);yield s.list("INSTALLMENT",uid).stream().filter(x->str(x,"loanId").equals(parts[1])).sorted(java.util.Comparator.comparingLong(x->num(x,"number"))).toList();}if(p.matches("splits/[^/]+")){var split=s.own("SPLIT",parts[1],uid);var requests=s.list("REQUEST",null).stream().filter(x->str(x,"splitId").equals(parts[1])).toList();split.put("requests",requests);split.put("paidCount",1+requests.stream().filter(x->str(x,"status").equals("PAID")).count());split.put("paidMinor",num(split,"organizerShareMinor")+requests.stream().filter(x->str(x,"status").equals("PAID")).mapToLong(x->num(x,"amountMinor")).sum());yield split;}if(p.matches("transfers/[^/]+"))yield publicTransfer(s.own("TRANSFER",parts[1],uid));throw new Problem(404,"NOT_FOUND","Səhifə tapılmadı.");}
  };
 }
 Map<String,Object> fraudMetrics(){long totalTx=Math.max(1,s.list("TX",null).size()),fraudCount=s.list("FRAUD",null).size();return m("traFraudRate",null,"calculationStatus","NOT_IMPLEMENTED","traActive",false,"openCases",s.list("FRAUD",null).stream().filter(x->"OPEN".equals(str(x,"status"))).count(),"totalCases",fraudCount,"monitoredTxCount",totalTx);}
 Map<String,Object> transferView(Map<String,Object> transfer,Integer avatar){
  var safe=new LinkedHashMap<>(transfer);safe.remove("recipientPhone");
  if("INTERNAL".equals(str(transfer,"channel"))){if(avatar==null)safe.remove("avatar");else safe.put("avatar",avatar);}
  return safe;
 }
 Map<String,Object> publicTransfer(Map<String,Object> transfer){
  String id=str(transfer,"recipientId");Integer avatar="INTERNAL".equals(str(transfer,"channel"))&&!id.isBlank()?auth.avatarOf(id):null;
  return transferView(transfer,avatar);
 }
 List<Map<String,Object>> publicTransfers(String uid){
  var transfers=s.list("TRANSFER",uid);
  var ids=transfers.stream().filter(t->"INTERNAL".equals(str(t,"channel"))).map(t->str(t,"recipientId")).filter(id->!id.isBlank()).distinct().toList();
  var avatars=new HashMap<String,Integer>();
  if(!ids.isEmpty()){
   String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
   for(var row:s.db.queryForList("SELECT id,avatar FROM users WHERE id IN ("+placeholders+")",ids.toArray()))if(row.get("avatar")!=null)avatars.put(row.get("id").toString(),((Number)row.get("avatar")).intValue());
  }
  return transfers.stream().map(t->transferView(t,avatars.get(str(t,"recipientId")))).toList();
 }
 List<Map<String,Object>> customerTransactions(String uid){return s.list("TX",uid).stream().filter(t->!yes(t,"demoHistory")).toList();}
 Map<String,Object> analytics(String uid){
  var totals=new LinkedHashMap<String,Long>();var now=Instant.now();var month=YearMonth.from(now.atZone(Insights.LOCAL));long external=0;
  for(var spend:Insights.spending(customerTransactions(uid),s.list("OB_TX",uid)))if(YearMonth.from(spend.at().atZone(Insights.LOCAL)).equals(month)){
   totals.merge(spend.category(),spend.amount(),Long::sum);if(spend.source().equals("BANK"))external+=spend.amount();
  }
  return m("month",month.toString(),"externalMinor",external,"totalMinor",totals.values().stream().mapToLong(Long::longValue).sum(),"categories",totals.entrySet().stream().map(e->m("category",e.getKey(),"amountMinor",e.getValue())).toList());
 }

 /** A transfer paid from a bank card that does not reach the recipient is returned to that card, not left in the wallet. */
 void returnToCard(Map<String,Object> t){
  if(str(t,"cardChargeId").isBlank()||yes(t,"returnedToCard"))return;
  long a=num(t,"amountMinor");String sender=str(t,"senderId");
  if(!openBanking.refundCharge(sender,str(t,"cardConsentRef"),str(t,"cardChargeId"),str(t,"cardAccountId"),a))return; // the bank could not take it back: the money stays in the wallet
  l.post("BANK_CARD_REFUND","Bank kartına qaytarma","wallet:"+sender,"CUSTOMER_FUNDS_BANK",a);
  t.put("returnedToCard",true);
 }
 Map<String,Object> compliance(){var result=creditPolicy.report();result.put("reconciliation",l.reconcile());return result;}
 @Scheduled(fixedDelayString="${kampus.ob-sync-ms:300000}",initialDelayString="${kampus.ob-sync-initial-ms:90000}") @Transactional public void syncOpenBanking(){s.lock();openBanking.syncAll(this::notify);}
 @Scheduled(fixedDelayString="${kampus.insights-scan-ms:120000}",initialDelayString="${kampus.insights-scan-initial-ms:60000}") @Transactional public void scanInsights(){s.lock();insights.scan(this::notify);}
 @Scheduled(fixedDelay=400) @Transactional public void processTransfers(){s.lock();for(var t:s.list("TRANSFER",null)){if(str(t,"status").equals("SCHEDULED")&&num(t,"executeAt")<=System.currentTimeMillis()){
   if(isBlacklisted(str(t,"phone"))||isBlacklisted(str(t,"recipientPhone"))){l.release(str(t,"holdId"));returnToCard(t);t.put("status","REJECTED");s.save("TRANSFER",t);notify(str(t,"senderId"),"Köçürmə bloklandı","Alan qara siyahıya əlavə edilib. Rezerv açıldı.");continue;}
   String sender=str(t,"senderId"),to=str(t,"recipientId");long a=num(t,"amountMinor");boolean external=str(t,"channel").equals("ANIPAY");boolean ok=true;
   if(external){try{sim.call("/anipay/transfers",m("transferId",t.get("id"),"amountMinor",a));}catch(Problem e){ok=false;}}
   l.release(str(t,"holdId"));if(!ok)returnToCard(t);if(ok){l.post("P2P","Köçürmə","wallet:"+sender,external?"ANIPAY_SETTLEMENT":"wallet:"+to,a);if(external)payout("ANIPAY_SETTLEMENT",a);String note=str(t,"note").trim();withNote(tx(sender,"P2P",str(t,"recipientName"),a,"OUT","Köçürmələr"),note);if(!external){withNote(tx(to,"P2P",s.db.queryForObject("SELECT name FROM users WHERE id=?",String.class,sender),a,"IN","Daxilolmalar"),note);notify(to,"Pul gəldi",note.isBlank()?"Kampus köçürməsi balansınıza əlavə edildi.":"Qeyd: "+note);}}
   t.put("status",ok?"COMPLETED":"REJECTED");s.save("TRANSFER",t);notify(sender,ok?"Köçürmə tamamlandı":"Köçürmə alınmadı",ok?str(t,"recipientName"):"Rezerv edilmiş vəsait açıldı.");
  }}
  for(var f:s.list("FRAUD",null))if(str(f,"status").equals("OPEN")&&num(f,"expiresAt")<System.currentTimeMillis()){var t=s.get("TRANSFER",str(f,"transferId"));l.release(str(t,"holdId"));returnToCard(t);t.put("status","REJECTED");s.save("TRANSFER",t);f.put("status","EXPIRED");s.save("FRAUD",f);}
 }
}
