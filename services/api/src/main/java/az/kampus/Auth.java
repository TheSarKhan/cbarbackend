package az.kampus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import java.util.*;
import java.time.*;
import static az.kampus.Store.*;
@Service public class Auth {
 final Store s; final Ledger l; final Simulator sim; final KycMedia media; final Tokens tokens; final Argon2PasswordEncoder hash=Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
 /** From this amount up (qəpik) a confirmed PIN is not enough: the customer must also pass a fresh face check. */
 final long kycStepUpMinor;
 Auth(Store s,Ledger l,Simulator sim,KycMedia media,Tokens tokens,@org.springframework.beans.factory.annotation.Value("${kampus.kyc-step-up-minor:50000}")long kycStepUpMinor){this.s=s;this.l=l;this.sim=sim;this.media=media;this.tokens=tokens;this.kycStepUpMinor=kycStepUpMinor;}
 /** Largest money amount a confirmation payload ({action,data}) moves; 0 when it carries none. */
 long payloadAmount(Object payload){
  if(!(payload instanceof Map<?,?> wrapper)||!(wrapper.get("data") instanceof Map<?,?> data))return 0;
  long largest=0;
  for(String key:new String[]{"amountMinor","totalMinor"}){
   Object value=data.get(key);if(value==null)continue;
   try{largest=Math.max(largest,new java.math.BigDecimal(value.toString()).longValueExact());}catch(Exception ignored){}
  }
  return largest;
 }
 boolean needsKyc(Object payload){return payloadAmount(payload)>=kycStepUpMinor;}
 /** Other beans call this through Spring's proxy, so read the limit with a method: a field read on the proxy would see 0. */
 long kycLimit(){return kycStepUpMinor;}
 static String sha256(String v){try{var md=java.security.MessageDigest.getInstance("SHA-256");return java.util.HexFormat.of().formatHex(md.digest(v.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new RuntimeException(e);}}
 record User(String id,String name,String phone,String role,String device,int pinLength){User(String id,String name,String phone,String role,String device){this(id,name,phone,role,device,6);}}
 Map<String,Object> publicUser(User u){return m("id",u.id,"name",u.name,"phone",u.phone,"role",u.role,"pinLength",u.pinLength);}
 User user(String authorization,String device){
  if(authorization==null||!authorization.startsWith("Bearer "))throw new Problem(401,"UNAUTHENTICATED","Yenidən daxil olun.");
  String token=authorization.substring(7);var rows=s.db.queryForList("SELECT u.id,u.name,u.phone,u.role,u.pin_length,se.device_id,se.expires_at FROM sessions se JOIN users u ON u.id=se.user_id WHERE token=?",token);
  if(rows.isEmpty())throw new Problem(401,"SESSION_EXPIRED","Sessiya bitib.");
  var r=rows.getFirst();if(((Number)r.get("expires_at")).longValue()<System.currentTimeMillis())throw new Problem(401,"SESSION_EXPIRED","Sessiya bitib.");
  if(!Objects.equals(device,r.get("device_id")))throw new Problem(401,"DEVICE_MISMATCH","Cihaz uyğun gəlmir.");
  String role=r.get("role").toString();s.db.update("UPDATE sessions SET expires_at=? WHERE token=?",System.currentTimeMillis()+("USER".equals(role)?300_000:1_800_000),token);
  return new User(r.get("id").toString(),r.get("name").toString(),r.get("phone").toString(),role,device,((Number)r.get("pin_length")).intValue());
 }
 void role(User u,String... roles){if(Arrays.stream(roles).noneMatch(u.role::equals))throw new Problem(403,"FORBIDDEN","Bu əməliyyat üçün icazəniz yoxdur.");}
 String encodePin(String pin){return hash.encode(pin);}
 boolean pin(String user,String pin){return hash.matches(pin,s.db.queryForObject("SELECT pin_hash FROM users WHERE id=?",String.class,user));}
 @Transactional(noRollbackFor=CredentialFailure.class) public Map<String,Object> login(Map<String,Object> b){
  s.lock();
  String phone=str(b,"phone"),device=str(b,"deviceId");Problem.require(!device.isBlank()&&device.length()<=128,"DEVICE_REQUIRED","Cihaz identifikatoru lazımdır.");
  var users=s.db.queryForList("SELECT * FROM users WHERE phone=?",phone);
  if(users.isEmpty())throw new Problem(401,"INVALID_CREDENTIALS","Telefon və ya PIN yanlışdır.");
  var u=users.getFirst();String id=u.get("id").toString();
  if(((Number)u.get("locked_until")).longValue()>System.currentTimeMillis())throw new Problem(423,"ACCOUNT_LOCKED","Hesab 30 dəqiqəlik bloklanıb.");
  if(!hash.matches(str(b,"pin"),str(u,"pin_hash"))){failure(id);throw new CredentialFailure("Telefon və ya PIN yanlışdır.");}
  s.db.update("UPDATE users SET failed_attempts=0,locked_until=0 WHERE id=?",id);
  String token=id()+id();s.db.update("INSERT INTO sessions VALUES(?,?,?,?)",token,id,device,System.currentTimeMillis()+("USER".equals(u.get("role"))?300_000:1_800_000));
  s.audit(id,"LOGIN",m("deviceId",device));return m("accessToken",token,"user",m("id",id,"name",u.get("name"),"phone",phone,"role",u.get("role"),"pinLength",u.get("pin_length")),"expiresIn",300);
 }
 /** Phone-first entry: tells the app whether to ask for a PIN or start registration. */
 Map<String,Object> identify(Map<String,Object>b){String phone=str(b,"phone");Problem.require(phone.matches("\\+994[0-9]{9}"),"INVALID_PHONE","+994 ilə başlayan nömrə yazın.");
  var known=s.db.queryForList("SELECT pin_length FROM users WHERE phone=?",phone);
  return known.isEmpty()?m("registered",false):m("registered",true,"pinLength",known.getFirst().get("pin_length"));
 }
 /** Confirms the SMS code on its own step; five wrong attempts burn the code. Attempt counts survive the error response. */
 @Transactional(noRollbackFor=Problem.class) Map<String,Object> verifyOtp(Map<String,Object>b){s.lock();var o=s.get("OTP",str(b,"otpId"));
  Problem.require(!yes(o,"used")&&num(o,"expiresAt")>System.currentTimeMillis(),"INVALID_OTP","SMS kodunun vaxtı bitib. Yeni kod istəyin.");
  if(!str(o,"code").equals(str(b,"otp"))){long attempts=num(o,"attempts")+1;o.put("attempts",attempts);
   if(attempts>=5)o.put("used",true);
   s.save("OTP",o);
   if(attempts>=5)throw new Problem(429,"OTP_LOCKED","Çox sayda yanlış cəhd. Yeni kod istəyin.");
   throw new Problem(422,"INVALID_OTP","SMS kodu yanlışdır.");
  }
  o.put("verified",true);o.put("registerUntil",System.currentTimeMillis()+1_800_000);s.save("OTP",o);
  return m("verified",true);
 }
 @Transactional Map<String,Object> otp(Map<String,Object>b){s.lock();String phone=str(b,"phone");Problem.require(phone.matches("\\+994[0-9]{9}"),"INVALID_PHONE","+994 ilə başlayan nömrə yazın.");
  var recent=s.list("OTP",phone);Problem.require(recent.stream().noneMatch(x->num(x,"expiresAt")>System.currentTimeMillis()+240_000),"OTP_RATE_LIMIT","Bir dəqiqə sonra yenidən sınayın.");
  String code=String.format("%06d",new java.security.SecureRandom().nextInt(1_000_000));
  var actions=new ArrayList<>(List.of("TURN_LEFT","TURN_RIGHT","BLINK","SMILE"));Collections.shuffle(actions);var liveness=actions.subList(0,3);
  var o=s.add("OTP",phone,m("phone",phone,"code",code,"livenessActions",liveness,"expiresAt",System.currentTimeMillis()+300_000,"attempts",0,"used",false));
  return m("otpId",o.get("id"),"demoCode",code,"livenessActions",liveness,"demo",true);
 }
 static final String NAME_PATTERN="[\\p{L}][\\p{L} '\\-]{1,59}";
 @Transactional Map<String,Object> register(Map<String,Object>b){s.lock();var o=s.get("OTP",str(b,"otpId"));
  Problem.require(!yes(o,"used")&&yes(o,"verified")&&Math.max(num(o,"expiresAt"),o.containsKey("registerUntil")?num(o,"registerUntil"):0L)>System.currentTimeMillis()&&str(o,"code").equals(str(b,"otp")),"INVALID_OTP","SMS kodu təsdiqlənməyib və ya vaxtı bitib. Yeni kod alın.");
  String pin=str(b,"pin"),first=str(b,"name").trim(),last=str(b,"surname").trim(),phone=str(o,"phone"),device=str(b,"deviceId"),fin=str(b,"fin").trim().toUpperCase(Locale.ROOT),series=str(b,"idSeries").replace(" ","").toUpperCase(Locale.ROOT),mode=str(b,"livenessMode");
  Problem.require(pin.matches("[0-9]{4}")&&!device.isBlank(),"INVALID_IDENTITY","Cihaz və 4 rəqəmli PIN tələb olunur.");
  Problem.require(first.matches(NAME_PATTERN)&&last.matches(NAME_PATTERN),"INVALID_NAME","Ad və soyadı düzgün yazın.");
  String name=first+" "+last;
  Problem.require(yes(b,"termsAccepted")&&yes(b,"dataConsent"),"CONSENT_REQUIRED","Şərtlərə və məlumatların emalına razılıq verin.");
  Problem.require(fin.matches("[A-Z0-9]{7}"),"FIN_REQUIRED","FİN kod 7 hərf və rəqəmdən ibarət olmalıdır.");
  Problem.require(series.matches("AZE[0-9]{8}|AA[0-9]{7}"),"ID_SERIES_REQUIRED","ŞV seriyası AZE12345678 və ya AA1234567 formatında olmalıdır.");
  Problem.require(b.containsKey("facesCount")&&b.containsKey("simaSigned"),"KYC_REQUIRED","Kamera və SİMA addımlarını tamamlayın.");
  Problem.require(num(b,"facesCount")==1,"MULTIPLE_FACES","Kadrda yalnız 1 üz olmalıdır.");
  Problem.require(yes(b,"simaSigned"),"SIMA_REQUIRED","Müqavilə SİMA İmza ilə imzalanmalıdır.");
  Problem.require("MEDIAPIPE_WEB".equals(mode),"LIVENESS_REQUIRED","Kamera ilə canlılıq yoxlamasını tamamlayın.");
  Object issued=o.get("livenessActions"),done=b.get("livenessActions");
  Problem.require(issued!=null&&issued.equals(done),"LIVENESS_REQUIRED","Canlılıq hərəkətlərini kamerada tamamlayın.");
  byte[] selfie=KycMedia.parseSelfie(str(b,"selfie"));
  Problem.require(s.list("KYC",null).stream().noneMatch(k->tokens.has(k,"fin","fin",fin)),"FIN_EXISTS","Bu FİN ilə artıq hesab mövcuddur.");
  Problem.require(s.list("KYC",null).stream().noneMatch(k->tokens.has(k,"idSeries","series",series)),"ID_SERIES_EXISTS","Bu ŞV seriyası ilə artıq hesab mövcuddur.");
  var reg=sim.call("/registry/lookup",m("fin",fin,"idSeries",series,"birthDate",str(b,"birthDate")));
  double faceScore=((Number)reg.getOrDefault("faceMatchScore",0.96)).doubleValue();
  Problem.require(faceScore>=0.80,"REJECTED_FACE_MATCH","Üz tutuşdurma balı aşağıdır (< 0.80).");
  LocalDate birth;try{birth=LocalDate.parse(str(reg,"birthDate"));}catch(Exception e){throw new Problem(422,"INVALID_BIRTH_DATE","Doğum tarixini düzgün daxil edin.");}
  Problem.require(!birth.isAfter(LocalDate.now().minusYears(18)),"AGE_RESTRICTED","Hesab yalnız 18 yaşdan yuxarı şəxslər üçündür.");
  Problem.require(s.db.queryForObject("SELECT COUNT(*) FROM users WHERE phone=?",Long.class,phone)==0,"PHONE_EXISTS","Bu nömrə artıq qeydiyyatdan keçib.");
  var sima=sim.call("/sima/sign",m("phone",phone,"name",name));
  String user=id();s.db.update("INSERT INTO users(id,phone,name,pin_hash,role,device_id,birth_date,created_at,pin_length) VALUES(?,?,?,?,?,?,?,?,4)",user,phone,name,hash.encode(pin),"USER",device,birth.toString(),now());l.account("wallet:"+user,"C");o.put("used",true);s.save("OTP",o);
  s.add("CONSENT",user,m("type","TERMS_AND_DATA","version","1","acceptedAt",now()));
  String mediaId=media.store(user,"SELFIE","image/jpeg",selfie);
  s.add("KYC",user,tokens.protectedRecord(m("userId",user,"userName",name,"firstName",first,"lastName",last,"phone",phone,"fin",fin,"idSeries",series,"status","DEMO_VERIFIED","provider","MOCK_REGISTRY_SIMA","liveness","CLIENT_REPORTED","livenessProvider",mode,"livenessActions",done,"faceMatchScore",faceScore,"contractId",sima.get("contractId"),"signatureHash",sima.get("signatureHash"),"mediaStored",true,"selfieMediaId",mediaId,"selfieSha256",KycMedia.sha256(selfie),"birthDate",birth.toString())));
  return login(m("phone",phone,"pin",pin,"deviceId",device));
 }
 @Transactional(noRollbackFor=CredentialFailure.class) public Map<String,Object> sca(User u,Map<String,Object>b){s.lock();
  if(!pin(u.id,str(b,"pin"))){failure(u.id);throw new CredentialFailure("PIN yanlışdır.");}
  s.db.update("UPDATE users SET failed_attempts=0 WHERE id=?",u.id);
  boolean kyc=needsKyc(b.get("payload"));
  var actions=new ArrayList<>(List.of("TURN_LEFT","TURN_RIGHT","BLINK","SMILE"));Collections.shuffle(actions);
  var proof=s.add("SCA",u.id,m("payload",b.get("payload"),"expiresAt",System.currentTimeMillis()+(kyc?180_000:60_000),"used",false,"deviceId",u.device,"kycRequired",kyc,"kycDone",false,"kycActions",kyc?new ArrayList<>(actions.subList(0,3)):List.of()));
  s.audit(u.id,"SCA_PIN_CONFIRMED",b.get("payload"));
  return kyc?m("proof",proof.get("id"),"kycRequired",true,"kycActions",proof.get("kycActions")):m("proof",proof.get("id"),"kycRequired",false);
 }
 /** Second factor for large amounts: a fresh liveness check. The selfie is kept encrypted as evidence for this payment. */
 /** The camera check shared by payment step-up and document signing: MediaPipe in the app, the actions the server asked for, one face, a real JPEG. */
 byte[] verifyLiveness(Map<String,Object> b,Object expectedActions){
  Problem.require("MEDIAPIPE_WEB".equals(str(b,"livenessMode")),"LIVENESS_REQUIRED","Kamera ilə canlılıq yoxlamasını tamamlayın.");
  Problem.require(expectedActions.equals(b.get("livenessActions")),"LIVENESS_REQUIRED","Canlılıq hərəkətlərini kamerada tamamlayın.");
  Problem.require(b.containsKey("facesCount")&&num(b,"facesCount")==1,"MULTIPLE_FACES","Kadrda yalnız 1 üz olmalıdır.");
  return KycMedia.parseSelfie(str(b,"selfie"));
 }
 @Transactional Map<String,Object> kycConfirm(User u,Map<String,Object> b){s.lock();
  var p=s.own("SCA",str(b,"proof"),u.id);
  Problem.require(yes(p,"kycRequired")&&!yes(p,"kycDone")&&!yes(p,"used")&&num(p,"expiresAt")>System.currentTimeMillis()&&str(p,"deviceId").equals(u.device),"KYC_NOT_PENDING","Üz təsdiqi tələb olunmur və ya vaxtı bitib.");
  byte[] selfie=verifyLiveness(b,p.get("kycActions"));
  String mediaId=media.store(u.id,"PAYMENT_SELFIE","image/jpeg",selfie);
  p.put("kycDone",true);p.put("kycMediaId",mediaId);p.put("expiresAt",System.currentTimeMillis()+90_000);s.save("SCA",p);
  s.audit(u.id,"KYC_STEP_UP_CONFIRMED",m("proof",p.get("id"),"mediaId",mediaId,"selfieSha256",KycMedia.sha256(selfie),"payload",p.get("payload")));
  return m("proof",p.get("id"),"kycDone",true);
 }
 void failure(String user){var r=s.db.queryForMap("SELECT failed_attempts FROM users WHERE id=?",user);int n=((Number)r.get("failed_attempts")).intValue()+1;s.db.update("UPDATE users SET failed_attempts=?,locked_until=? WHERE id=?",n,n>=3?System.currentTimeMillis()+1_800_000:0,user);if(n>=3){s.db.update("DELETE FROM sessions WHERE user_id=?",user);s.add("NOTIFICATION",user,m("title","Hesab bloklandı","body","3 səhv PIN cəhdi. Hesabınız 30 dəqiqəlik bloklandı.","read",false));s.audit(user,"PIN_LOCKOUT",m("attempts",n));}}
 void requireSca(User u,String proof,String action,Map<String,Object> payload){if(proof==null||proof.isBlank())throw new Problem(403,"SCA_REQUIRED","Məbləğ və alanı PIN ilə təsdiqləyin.");
  var p=s.own("SCA",proof,u.id);Problem.require(!yes(p,"used")&&num(p,"expiresAt")>System.currentTimeMillis()&&str(p,"deviceId").equals(u.device),"SCA_EXPIRED","Təsdiqin vaxtı bitib.");
  var expected=m("action",action,"data",payload);Problem.require(s.encode(p.get("payload")).equals(s.encode(expected)),"SCA_BINDING_MISMATCH","Təsdiq məbləğə və alana uyğun gəlmir.");if(needsKyc(expected)&&!yes(p,"kycDone"))throw new Problem(403,"KYC_REQUIRED","Böyük məbləğ üçün üzünüzü də təsdiqləyin.");p.put("used",true);s.save("SCA",p);for(var counter:s.list("EXEMPT_COUNTER",u.id)){counter.put("count",0);counter.put("sumMinor",0);s.save("EXEMPT_COUNTER",counter);}
 }
}
