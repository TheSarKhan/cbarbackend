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
 /** An AniPay recipient exists only once they chose a receiving card or account in their bank app. */
 Map<String,Object> aniPayRecipient(String identifier,boolean isFin){
  var r=sim.call("/anipay/lookup",isFin?m("fin",identifier):m("phone",identifier));
  if(!yes(r,"registered"))throw new Problem(404,"RECIPIENT_NOT_FOUND","Bu "+(isFin?"FİN kod":"nömrə")+" AniPay-də qeydiyyatdan keçməyib. Alıcı öz bank proqramında AniPay-ə qoşulmalıdır.");
  return m("channel","ANIPAY","recipientId","ANIPAY_SETTLEMENT","maskedName",r.get("bankName")+" müştərisi","bankName",r.get("bankName"),"phone",identifier);
 }
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
  // any other bank customer is reached through AniPay, if they have set it up in their bank app
  if(phone.matches("\\+994(50|51|55|60|70|77|99|10)[0-9]{7}"))return aniPayRecipient(phone,false);
  // AniPay also takes the recipient's FİN instead of the phone
  if(phone.matches("[A-Z0-9]{7}"))return aniPayRecipient(phone,true);
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
  for(var f:s.list("FRIEND",null))if(uid.equals(str(f,"friendId")))s.db.update("DELETE FROM records WHERE kind='FRIEND' AND id=?",f.get("id"));
  s.db.update("DELETE FROM records WHERE owner_id=? OR (kind='OTP' AND owner_id=?)",uid,u.phone());
  s.db.update("DELETE FROM users WHERE id=?",uid);
  return m("deleted",true);
 }
 /** A friend as the other person sees them: enough to send money, nothing else. */
 Map<String,Object> friendView(String userId){var r=s.db.queryForMap("SELECT id,name,phone,avatar FROM users WHERE id=?",userId);return m("id",r.get("id"),"name",r.get("name"),"phone",r.get("phone"),"avatar",r.get("avatar"));}
 List<Map<String,Object>> friends(String uid){var out=new ArrayList<Map<String,Object>>();for(var f:s.list("FRIEND",uid)){try{var v=new LinkedHashMap<>(friendView(str(f,"friendId")));v.put("since",f.get("createdAt"));out.add(v);}catch(RuntimeException gone){/* the friend deleted the account */}}return out;}
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
 static boolean luhn(String n){int sum=0;boolean dbl=false;for(int i=n.length()-1;i>=0;i--){int d=n.charAt(i)-'0';if(dbl){d*=2;if(d>9)d-=9;}sum+=d;dbl=!dbl;}return sum%10==0;}
 /** Issuer-side outcomes of the card simulator: a card ending 0002 is declined, 9995 has no funds. */
 void issuerDecline(String last4){Problem.require(!last4.equals("0002"),"CARD_DECLINED","Kart bank tərəfindən rədd edildi.");Problem.require(!last4.equals("9995"),"CARD_NO_FUNDS","Kartda kifayət qədər vəsait yoxdur.");}
 /** Validates a typed card and returns only what may be kept: brand, last four, expiry. The number and CVV are never stored. */
 @SuppressWarnings("unchecked") Map<String,Object> cardCheck(Object raw){
  Problem.require(raw instanceof Map,"INVALID_CARD","Kart məlumatlarını daxil edin.");var c=(Map<String,Object>)raw;
  String number=str(c,"number").replaceAll("[ -]",""),cvv=str(c,"cvv"),exp=str(c,"expiry");
  Problem.require(number.matches("\\d{16}")&&luhn(number),"INVALID_CARD","Kart nömrəsi yanlışdır.");
  var mt=java.util.regex.Pattern.compile("(0[1-9]|1[0-2])/(\\d{2})").matcher(exp);Problem.require(mt.matches(),"INVALID_CARD","Müddəti AA/İİ formatında daxil edin.");
  Problem.require(!java.time.YearMonth.of(2000+Integer.parseInt(mt.group(2)),Integer.parseInt(mt.group(1))).isBefore(java.time.YearMonth.now()),"CARD_EXPIRED","Kartın müddəti bitib.");
  Problem.require(cvv.matches("\\d{3}"),"INVALID_CARD","CVV 3 rəqəm olmalıdır.");
  String last4=number.substring(12),brand=number.startsWith("4")?"Visa":number.matches("^(5[1-5]|2[2-7]).*")?"Mastercard":"Kart";issuerDecline(last4);
  return m("brand",brand,"last4",last4,"expiry",exp,"label",brand+" •••• "+last4);
 }
 /** Keeps a token for the card, not the card: the next top-up needs one tap and no CVV. */
 Map<String,Object> saveCard(String uid,Map<String,Object> card){var same=s.list("SAVED_CARD",uid).stream().filter(x->str(x,"label").equals(str(card,"label"))&&str(x,"expiry").equals(str(card,"expiry"))).findFirst();if(same.isPresent())return same.get();var saved=new LinkedHashMap<>(card);saved.put("token","tok_"+id().replace("-",""));return s.add("SAVED_CARD",uid,saved);}
 static Map<String,Object> redacted(Map<String,Object> b){if(b.containsKey("code")){var r=new LinkedHashMap<>(b);r.put("code","••••••");return r;}if(b.get("number") instanceof String n){var r=new LinkedHashMap<>(b);String d=n.replaceAll("[ -]","");r.put("number","•••• "+(d.length()>=4?d.substring(d.length()-4):""));return r;}if(!(b.get("card") instanceof Map<?,?>c))return b;var r=new LinkedHashMap<>(b);String n=Objects.toString(c.get("number"),"").replaceAll("[ -]","");r.put("card",m("last4",n.length()>=4?n.substring(n.length()-4):""));return r;}
 void scaIf(Auth.User u,String proof,String action,Map<String,Object>b,boolean required){long a=amount(b);var counter=s.list("EXEMPT_COUNTER",u.id());long count=counter.isEmpty()?0:num(counter.getFirst(),"count"),sum=counter.isEmpty()?0:num(counter.getFirst(),"sumMinor");if(required||count>=5||sum+a>30000)auth.requireSca(u,proof,action,b);else {var state=counter.isEmpty()?s.add("EXEMPT_COUNTER",u.id(),m("count",0,"sumMinor",0)):counter.getFirst();state.put("count",count+1);state.put("sumMinor",sum+a);s.save("EXEMPT_COUNTER",state);}}
 @Transactional public Object write(Auth.User u,String path,Map<String,Object>b,String key,String proof){
  s.lock();Problem.require(key!=null&&key.matches("[A-Za-z0-9_-]{8,128}"),"IDEMPOTENCY_REQUIRED","Unikal Idempotency-Key tələb olunur.");
  String fingerprint=Auth.sha256(path+"|"+s.encode(b));var prev=s.db.queryForList("SELECT fingerprint,response FROM idempotency WHERE user_id=? AND idem_key=?",u.id(),key);
  if(!prev.isEmpty()){Problem.require(fingerprint.equals(prev.getFirst().get("fingerprint")),"IDEMPOTENCY_CONFLICT","Bu açar başqa sorğuda istifadə olunub.");if(path.matches("cards/[^/]+/reveal"))throw new Problem(410,"SENSITIVE_REPLAY_EXPIRED","Kart məlumatlarını yeni PIN təsdiqi ilə yenidən açın.");return s.decode(prev.getFirst().get("response").toString());}
  Object result;Simulator.operation.set(Auth.sha256(u.id()+"|"+key));try{result=execute(u,path,b,proof);}finally{Simulator.operation.remove();}s.db.update("INSERT INTO idempotency VALUES(?,?,?,?)",u.id(),key,fingerprint,s.encode(path.matches("cards/[^/]+/reveal")?m("sensitive",true):result));s.audit(u.id(),path,redacted(b));return result;
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
  if(p.equals("topups")){long a=amount(b);Problem.require(l.balance("wallet:"+uid)+a<=200_000,"WALLET_LIMIT","Demo hesabının balans limiti 2 000 AZN-dir.");String source=str(b,"source");Problem.require(Set.of("NEW_CARD","SAVED_CARD","BANK_CARD").contains(source),"INVALID_SOURCE","Doldurma üsulunu seçin.");String label;
   if(source.equals("BANK_CARD")){scaIf(u,proof,p,b,true);label=str(openBanking.charge(uid,str(b,"accountId"),a,"Balans artırma"),"cardLabel");}
   else if(source.equals("SAVED_CARD")){var c=s.own("SAVED_CARD",str(b,"cardId"),uid);scaIf(u,proof,p,b,true);issuerDecline(str(c,"last4"));sim.call("/bank/topup",m("amountMinor",a,"source","CARD","cardToken",c.get("token")));label=str(c,"label");}
   else{var card=cardCheck(b.get("card"));boolean save=yes(b,"saveCard");scaIf(u,proof,p,b,save);sim.call("/bank/topup",m("amountMinor",a,"source","CARD","last4",card.get("last4")));label=str(card,"label");if(save)saveCard(uid,card);}
   l.post("TOPUP","Demo balans artırma","CUSTOMER_FUNDS_BANK","wallet:"+uid,a);notify(uid,"Balans artırıldı",String.format(Locale.ROOT,"%.2f AZN",a/100.0));return tx(uid,"TOPUP","Balans artırma · "+label,a,"IN","Daxilolmalar");}
  if(p.equals("saved-cards")){var card=cardCheck(b.get("card"));auth.requireSca(u,proof,p,b);var saved=new LinkedHashMap<>(saveCard(uid,card));saved.remove("token");return saved;}
  if(p.matches("saved-cards/[^/]+/delete")){var c=s.own("SAVED_CARD",parts[1],uid);s.db.update("DELETE FROM records WHERE kind='SAVED_CARD' AND id=?",c.get("id"));return m("deleted",true,"id",c.get("id"));}
  if(p.equals("topups/qr")){var mine=s.list("TOPUP_QR",uid);if(!mine.isEmpty())return mine.getFirst();String qid=id();return s.add("TOPUP_QR",uid,m("id",qid,"requesterName",u.name(),"status","ACTIVE","totalMinor",0,"paidCount",0,"payments",new ArrayList<>(),"qrPayload","kampus-topup:"+qid));}
  if(p.matches("topups/qr/[^/]+/pay")){var q=s.get("TOPUP_QR",parts[2]);String owner=s.db.queryForObject("SELECT owner_id FROM records WHERE kind='TOPUP_QR' AND id=?",String.class,parts[2]);Problem.require(!owner.equals(uid),"INVALID_RECIPIENT","Öz QR kodunuzu ödəyə bilməzsiniz.");long a=amount(b);Problem.require(a<=100_000,"AMOUNT_LIMIT","Bir ödəniş 1 000 AZN-dən çox ola bilməz.");auth.requireSca(u,proof,p,b);Problem.require(l.available("wallet:"+uid)>=a,"INSUFFICIENT_FUNDS","Balans kifayət etmir.");Problem.require(l.balance("wallet:"+owner)+a<=200_000,"WALLET_LIMIT","Alıcının balans limiti dolub.");
   l.post("TOPUP_QR","QR ilə balans artırma","wallet:"+uid,"wallet:"+owner,a);tx(uid,"P2P",str(q,"requesterName"),a,"OUT","Köçürmələr");tx(owner,"TOPUP","QR · "+u.name(),a,"IN","Daxilolmalar");notify(owner,"Balans artırıldı",String.format(Locale.ROOT,"%s sizin QR kodunuzla %.2f AZN köçürdü",u.name(),a/100.0));
   var payments=new ArrayList<Object>();payments.add(m("payerName",u.name(),"amountMinor",a,"at",System.currentTimeMillis()));if(q.get("payments") instanceof List<?> old)old.stream().limit(9).forEach(payments::add);
   q.put("payments",payments);q.put("totalMinor",num(q,"totalMinor")+a);q.put("paidCount",num(q,"paidCount")+1);s.save("TOPUP_QR",q);return m("id",q.get("id"),"requesterName",q.get("requesterName"),"amountMinor",a,"status","PAID");}
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
  // The customer's bank app, simulated: FİN and phone, an SMS code, then the account that receives AniPay transfers.
  if(p.startsWith("anipay-bank/")){
   var allowed=Map.of("start",Set.of("bankId","fin","phone"),"verify",Set.of("sessionId","code"),"register",Set.of("sessionId","accountId"));
   Problem.require(parts.length==2&&allowed.containsKey(parts[1])&&allowed.get(parts[1]).containsAll(b.keySet()),"NOT_FOUND","Səhifə tapılmadı.");
   Map<String,Object> r;
   try{r=sim.call("/anipay/bank/"+parts[1],b);}catch(Problem refused){throw new Problem(422,"BANK_SESSION","Bank sessiyasının vaxtı bitib və ya məlumat yanlışdır. Yenidən başlayın.");}
   if(parts[1].equals("start"))Problem.require(yes(r,"customer"),"NOT_A_CUSTOMER","Bu FİN və nömrə ilə bu bankın müştərisi tapılmadı. Başqa bank seçin.");
   if(parts[1].equals("verify")&&!yes(r,"verified")){Problem.require(num(r,"attemptsLeft")>0,"OTP_LOCKED","Kod 3 dəfə yanlış daxil edildi. Yenidən başlayın.");throw new Problem(422,"OTP_INVALID","Kod yanlışdır. "+r.get("attemptsLeft")+" cəhd qalıb.");}
   return r;
  }
  // Kampus friends: a static personal QR; whoever scans it becomes a friend, and so does the owner.
  if(p.equals("friends/qr")){var mine=s.list("FRIEND_QR",uid);if(!mine.isEmpty())return mine.getFirst();String qid=id();return s.add("FRIEND_QR",uid,m("id",qid,"qrPayload","kampus-friend:"+qid));}
  if(p.equals("friends/add")){
   Problem.require(b.keySet().equals(Set.of("qrId")),"UNEXPECTED_FIELD","Yanlış sorğu.");
   String qid=str(b,"qrId");s.get("FRIEND_QR",qid);String owner=s.db.queryForObject("SELECT owner_id FROM records WHERE kind='FRIEND_QR' AND id=?",String.class,qid);
   Problem.require(!owner.equals(uid),"SELF_FRIEND","Öz QR kodunuzu oxuda bilməzsiniz.");
   if(s.list("FRIEND",uid).stream().noneMatch(f->owner.equals(str(f,"friendId")))){
    s.add("FRIEND",uid,m("friendId",owner));s.add("FRIEND",owner,m("friendId",uid));
    notify(owner,"Yeni Kampus dostu",u.name()+" ilə indi dostsunuz.");
   }
   return friendView(owner);
  }
  if(p.matches("friends/[^/]+/remove")){
   String other=parts[1];Problem.require(s.list("FRIEND",uid).stream().anyMatch(f->other.equals(str(f,"friendId"))),"NOT_FOUND","Dost tapılmadı.");
   for(var f:s.list("FRIEND",uid))if(other.equals(str(f,"friendId")))s.db.update("DELETE FROM records WHERE kind='FRIEND' AND id=?",f.get("id"));
   for(var f:s.list("FRIEND",other))if(uid.equals(str(f,"friendId")))s.db.update("DELETE FROM records WHERE kind='FRIEND' AND id=?",f.get("id"));
   return m("removed",true,"id",other);
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
  if(source.equals("BANK_CARD")){openBanking.charge(uid,str(b,"accountId"),a,"QR · "+str(qr,"merchantName"));l.post("BANK_CARD_FUNDING","Bank kartından maliyyələşmə","CUSTOMER_FUNDS_BANK","wallet:"+uid,a);debit="wallet:"+uid;}
  else {Problem.require(source.equals("WALLET"),"INVALID_SOURCE","Ödəniş mənbəyi yanlışdır.");Problem.require(l.available("wallet:"+uid)>=a,"INSUFFICIENT_FUNDS","Balans kifayət etmir.");debit="wallet:"+uid;}
  l.post("QR_PAYMENT","Kafe Xazar",debit,"merchant:"+mid,a);long fee=(a*50+5000)/10000;if(fee>0){l.post("MERCHANT_FEE","Satıcı komissiyası","merchant:"+mid,"FEE_INCOME",fee);l.post("FEE_SWEEP","Komissiya ayrılması","OWN_FUNDS_BANK","CUSTOMER_FUNDS_BANK",fee);}
  var payment=s.add("PAYMENT",uid,m("id",payid,"merchantId",mid,"merchantName",qr.get("merchantName"),"amountMinor",a,"feeMinor",fee,"fundingSource",source,"status","COMPLETED","payerId",uid));
  if(!str(qr,"intentId").isBlank()){var i=s.get("INTENT",str(qr,"intentId"));i.put("status","PAID");i.put("paymentId",payid);s.save("INTENT",i);}
  tx(uid,"QR",str(qr,"merchantName"),a,"OUT","Yemək");notify(uid,"Ödəniş tamamlandı",str(qr,"merchantName"));return payment;
 }
 Object merchant(Auth.User u,String p,Map<String,Object>b){auth.role(u,"ADMIN","MERCHANT");String mid="m1";String[] parts=p.split("/");
  if(p.equals("merchant/qr/dynamic")){long a=amount(b);var i=s.add("INTENT",u.id(),m("merchantId",mid,"amountMinor",a,"status","OPEN","expiresAt",System.currentTimeMillis()+300000));i.put("qrPayload",EmvQr.create(mid,str(i,"id"),a));s.save("INTENT",i);return i;}
  if(p.matches("merchant/payments/[^/]+/refund")){var pay=s.get("PAYMENT",parts[2]);Problem.require(str(pay,"merchantId").equals(mid)&&str(pay,"status").equals("COMPLETED"),"REFUND_UNAVAILABLE","Ödəniş geri qaytarıla bilməz.");Problem.require(Instant.parse(str(pay,"createdAt")).plus(Duration.ofDays(30)).isAfter(Instant.now()),"REFUND_EXPIRED","Geri qaytarma müddəti bitib.");
   long a=num(pay,"amountMinor"),fee=num(pay,"feeMinor");String uid=str(pay,"payerId"),source=str(pay,"fundingSource");
   if(fee>0){l.post("FEE_REVERSAL","Komissiya geri qaytarma","CUSTOMER_FUNDS_BANK","OWN_FUNDS_BANK",fee);l.post("FEE_REVERSAL","Komissiya geri qaytarma","FEE_INCOME","merchant:"+mid,fee);}
   {l.post("REFUND","Geri qaytarma","merchant:"+mid,"wallet:"+uid,a);}
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
   case "dashboard" -> m("user",auth.publicUser(u),"wallet",l.wallet(uid),"transactions",customerTransactions(uid),"transfers",publicTransfers(uid),"trustedBeneficiaries",s.list("TRUSTED_BENEFICIARY",uid),"friends",friends(uid),"savedCards",s.list("SAVED_CARD",uid).stream().map(c->{var r=new LinkedHashMap<>(c);r.remove("token");return r;}).toList(),"topupQrs",s.list("TOPUP_QR",uid),"notifications",s.list("NOTIFICATION",uid),"analytics",analytics(uid),"insightsBrief",insights.brief(uid),"openBanking",openBanking.overview(uid),"budgets",s.list("BUDGET",uid),"cardTransfers",s.list("CARD_TRANSFER",uid).stream().map(cardTransfers::publicTransfer).toList(),"recipientCards",cardTransfers.read(u,"recipient-cards"));
   case "transactions" -> customerTransactions(uid);
   case "trusted-beneficiaries" -> s.list("TRUSTED_BENEFICIARY",uid);
   case "notifications" -> s.list("NOTIFICATION",uid);
   case "transfers/resolve" -> {var r=new LinkedHashMap<>(resolve(query));r.remove("recipientPhone");r.put("recipientToken",tokens.issue(uid,"P2P_RECIPIENT",m("phone",query),300));yield r;}
   case "budgets" -> s.list("BUDGET",uid);
   case "analytics/summary" -> analytics(uid);
   case "analytics/insights" -> insights.view(u,this::notify);
   default -> {String[] parts=p.split("/");if(p.matches("friends/qr/[^/]+")){var q=s.get("FRIEND_QR",parts[2]);String owner=s.db.queryForObject("SELECT owner_id FROM records WHERE kind='FRIEND_QR' AND id=?",String.class,parts[2]);var v=friendView(owner);yield m("id",q.get("id"),"name",v.get("name"),"avatar",v.get("avatar"),"self",owner.equals(uid),"alreadyFriend",s.list("FRIEND",uid).stream().anyMatch(f->owner.equals(str(f,"friendId"))));}if(p.matches("topups/qr/[^/]+")){var q=s.get("TOPUP_QR",parts[2]);yield m("id",q.get("id"),"requesterName",q.get("requesterName"),"status","ACTIVE");}if(p.matches("transfers/[^/]+"))yield publicTransfer(s.own("TRANSFER",parts[1],uid));throw new Problem(404,"NOT_FOUND","Səhifə tapılmadı.");}
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
   if(external){try{var paid=sim.call("/anipay/transfers",m("transferId",t.get("id"),"amountMinor",a,"fin",str(t,"phone").matches("[A-Z0-9]{7}")?str(t,"phone"):"","phone",str(t,"phone").matches("[A-Z0-9]{7}")?"":str(t,"phone"),"senderName",s.db.queryForObject("SELECT name FROM users WHERE id=?",String.class,sender)));ok=str(paid,"status").equals("COMPLETED");if(ok)t.put("receivedTo",str(paid,"receivedTo"));}catch(Problem e){ok=false;}}
   l.release(str(t,"holdId"));if(!ok)returnToCard(t);if(ok){l.post("P2P","Köçürmə","wallet:"+sender,external?"ANIPAY_SETTLEMENT":"wallet:"+to,a);if(external)payout("ANIPAY_SETTLEMENT",a);String note=str(t,"note").trim();withNote(tx(sender,"P2P",str(t,"recipientName"),a,"OUT","Köçürmələr"),note);if(!external){withNote(tx(to,"P2P",s.db.queryForObject("SELECT name FROM users WHERE id=?",String.class,sender),a,"IN","Daxilolmalar"),note);notify(to,"Pul gəldi",note.isBlank()?"Kampus köçürməsi balansınıza əlavə edildi.":"Qeyd: "+note);}}
   t.put("status",ok?"COMPLETED":"REJECTED");s.save("TRANSFER",t);notify(sender,ok?"Köçürmə tamamlandı":"Köçürmə alınmadı",ok?(external?str(t,"receivedTo"):str(t,"recipientName")):"Rezerv edilmiş vəsait açıldı.");
  }}
  for(var f:s.list("FRAUD",null))if(str(f,"status").equals("OPEN")&&num(f,"expiresAt")<System.currentTimeMillis()){var t=s.get("TRANSFER",str(f,"transferId"));l.release(str(t,"holdId"));returnToCard(t);t.put("status","REJECTED");s.save("TRANSFER",t);f.put("status","EXPIRED");s.save("FRAUD",f);}
 }
}
