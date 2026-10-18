package az.kampus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import java.util.*;
import java.time.*;
import static az.kampus.Store.*;
@Service public class Auth {
 final Store s; final Ledger l; final Simulator sim; final Argon2PasswordEncoder hash=Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
 Auth(Store s,Ledger l,Simulator sim){this.s=s;this.l=l;this.sim=sim;}
 static String sha256(String v){try{var md=java.security.MessageDigest.getInstance("SHA-256");return java.util.HexFormat.of().formatHex(md.digest(v.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new RuntimeException(e);}}
 record User(String id,String name,String phone,String role,String device){}
 Map<String,Object> publicUser(User u){return m("id",u.id,"name",u.name,"phone",u.phone,"role",u.role);}
 User user(String authorization,String device){
  if(authorization==null||!authorization.startsWith("Bearer "))throw new Problem(401,"UNAUTHENTICATED","Yenidən daxil olun.");
  String token=authorization.substring(7);var rows=s.db.queryForList("SELECT u.id,u.name,u.phone,u.role,se.device_id,se.expires_at FROM sessions se JOIN users u ON u.id=se.user_id WHERE token=?",token);
  if(rows.isEmpty())throw new Problem(401,"SESSION_EXPIRED","Sessiya bitib.");
  var r=rows.getFirst();if(((Number)r.get("expires_at")).longValue()<System.currentTimeMillis())throw new Problem(401,"SESSION_EXPIRED","Sessiya bitib.");
  if(!Objects.equals(device,r.get("device_id")))throw new Problem(401,"DEVICE_MISMATCH","Cihaz uyğun gəlmir.");
  String role=r.get("role").toString();s.db.update("UPDATE sessions SET expires_at=? WHERE token=?",System.currentTimeMillis()+("USER".equals(role)?300_000:1_800_000),token);
  return new User(r.get("id").toString(),r.get("name").toString(),r.get("phone").toString(),role,device);
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
  s.audit(id,"LOGIN",m("deviceId",device));return m("accessToken",token,"user",m("id",id,"name",u.get("name"),"phone",phone,"role",u.get("role")),"expiresIn",300);
 }
 @Transactional Map<String,Object> otp(Map<String,Object>b){s.lock();String phone=str(b,"phone");Problem.require(phone.matches("\\+994[0-9]{9}"),"INVALID_PHONE","+994 ilə başlayan nömrə yazın.");
  var recent=s.list("OTP",phone);Problem.require(recent.stream().noneMatch(x->num(x,"expiresAt")>System.currentTimeMillis()+240_000),"OTP_RATE_LIMIT","Bir dəqiqə sonra yenidən sınayın.");
  String code=String.format("%06d",new java.security.SecureRandom().nextInt(1_000_000));
  var actions=new ArrayList<>(List.of("TURN_LEFT","TURN_RIGHT","BLINK","SMILE"));Collections.shuffle(actions);var liveness=actions.subList(0,3);
  var o=s.add("OTP",phone,m("phone",phone,"code",code,"livenessActions",liveness,"expiresAt",System.currentTimeMillis()+300_000,"attempts",0,"used",false));
  return m("otpId",o.get("id"),"demoCode",code,"livenessActions",liveness,"demo",true);
 }
 @Transactional Map<String,Object> register(Map<String,Object>b){s.lock();var o=s.get("OTP",str(b,"otpId"));
  Problem.require(!yes(o,"used")&&num(o,"expiresAt")>System.currentTimeMillis()&&str(o,"code").equals(str(b,"otp")),"INVALID_OTP","SMS kodu yanlışdır və ya vaxtı bitib.");
  String pin=str(b,"pin"),name=str(b,"name"),phone=str(o,"phone"),device=str(b,"deviceId"),fin=str(b,"fin").trim().toUpperCase(Locale.ROOT);
  Problem.require(pin.matches("[0-9]{6}")&&name.length()>=3&&name.length()<100&&!device.isBlank(),"INVALID_IDENTITY","Ad, cihaz və 6 rəqəmli PIN tələb olunur.");
  Problem.require(yes(b,"termsAccepted")&&yes(b,"dataConsent"),"CONSENT_REQUIRED","Şərtlərə və məlumatların emalına razılıq verin.");
  Problem.require(b.containsKey("facesCount")&&b.containsKey("simaSigned"),"DEMO_KYC_REQUIRED","Demo canlılıq və SİMA addımlarını təsdiqləyin.");
  if(b.containsKey("facesCount"))Problem.require(num(b,"facesCount")==1,"MULTIPLE_FACES","Kadrda yalnız 1 üz olmalıdır.");
  if(b.containsKey("simaSigned"))Problem.require(yes(b,"simaSigned"),"SIMA_REQUIRED","Müqavilə SİMA İmza ilə imzalanmalıdır.");
  Problem.require(fin.matches("[A-Z0-9]{7}"),"FIN_REQUIRED","Demo FİN 7 hərf və rəqəmdən ibarət olmalıdır.");
  double faceScore=0.96;String birthRaw=str(b,"birthDate");
  if(!fin.isBlank()){
   Problem.require(s.list("KYC",null).stream().noneMatch(k->fin.equalsIgnoreCase(str(k,"fin"))),"FIN_EXISTS","Bu FİN ilə artıq hesab mövcuddur.");
   var reg=sim.call("/registry/lookup",m("fin",fin,"birthDate",birthRaw));
   faceScore=((Number)reg.getOrDefault("faceMatchScore",0.96)).doubleValue();
   birthRaw=str(reg,"birthDate");
   Problem.require(faceScore>=0.80,"REJECTED_FACE_MATCH","Üz tutuşdurma balı aşağıdır (< 0.80).");
  }
  LocalDate birth;try{birth=LocalDate.parse(birthRaw);}catch(Exception e){throw new Problem(422,"INVALID_BIRTH_DATE","Doğum tarixini düzgün daxil edin.");}
  Problem.require(!birth.isAfter(LocalDate.now().minusYears(18)),"AGE_RESTRICTED","Hesab yalnız 18 yaşdan yuxarı şəxslər üçündür.");
  Problem.require(s.db.queryForObject("SELECT COUNT(*) FROM users WHERE phone=?",Long.class,phone)==0,"PHONE_EXISTS","Bu nömrə artıq qeydiyyatdan keçib.");
  var sima=sim.call("/sima/sign",m("phone",phone,"name",name));
  String user=id();s.db.update("INSERT INTO users(id,phone,name,pin_hash,role,device_id,birth_date,created_at) VALUES(?,?,?,?,?,?,?,?)",user,phone,name,hash.encode(pin),"USER",device,birth.toString(),now());l.account("wallet:"+user,"C");o.put("used",true);s.save("OTP",o);
  s.add("CONSENT",user,m("type","TERMS_AND_DATA","version","1","acceptedAt",now()));
  s.add("KYC",user,m("userId",user,"userName",name,"phone",phone,"fin",fin.isBlank()?"DEMO-"+user.substring(0,5).toUpperCase(Locale.ROOT):fin,"status","DEMO_VERIFIED","provider","MOCK_REGISTRY_SIMA","liveness","DEMO_SIMULATED","livenessActions",o.getOrDefault("livenessActions",List.of("TURN_LEFT","BLINK","SMILE")),"faceMatchScore",faceScore,"contractId",sima.get("contractId"),"signatureHash",sima.get("signatureHash"),"mediaStored",false,"birthDate",birth.toString()));
  return login(m("phone",phone,"pin",pin,"deviceId",device));
 }
 @Transactional(noRollbackFor=CredentialFailure.class) public Map<String,Object> sca(User u,Map<String,Object>b){s.lock();
  if(!pin(u.id,str(b,"pin"))){failure(u.id);throw new CredentialFailure("PIN yanlışdır.");}
  s.db.update("UPDATE users SET failed_attempts=0 WHERE id=?",u.id);
  var proof=s.add("SCA",u.id,m("payload",b.get("payload"),"expiresAt",System.currentTimeMillis()+60_000,"used",false,"deviceId",u.device));
  s.audit(u.id,"SCA_PIN_CONFIRMED",b.get("payload"));return m("proof",proof.get("id"));
 }
 void failure(String user){var r=s.db.queryForMap("SELECT failed_attempts FROM users WHERE id=?",user);int n=((Number)r.get("failed_attempts")).intValue()+1;s.db.update("UPDATE users SET failed_attempts=?,locked_until=? WHERE id=?",n,n>=3?System.currentTimeMillis()+1_800_000:0,user);if(n>=3){s.db.update("DELETE FROM sessions WHERE user_id=?",user);s.add("NOTIFICATION",user,m("title","Hesab bloklandı","body","3 səhv PIN cəhdi. Hesabınız 30 dəqiqəlik bloklandı.","read",false));s.audit(user,"PIN_LOCKOUT",m("attempts",n));}}
 void requireSca(User u,String proof,String action,Map<String,Object> payload){if(proof==null||proof.isBlank())throw new Problem(403,"SCA_REQUIRED","Məbləğ və alanı PIN ilə təsdiqləyin.");
  var p=s.own("SCA",proof,u.id);Problem.require(!yes(p,"used")&&num(p,"expiresAt")>System.currentTimeMillis()&&str(p,"deviceId").equals(u.device),"SCA_EXPIRED","Təsdiqin vaxtı bitib.");
  var expected=m("action",action,"data",payload);Problem.require(s.encode(p.get("payload")).equals(s.encode(expected)),"SCA_BINDING_MISMATCH","Təsdiq məbləğə və alana uyğun gəlmir.");p.put("used",true);s.save("SCA",p);for(var counter:s.list("EXEMPT_COUNTER",u.id)){counter.put("count",0);counter.put("sumMinor",0);s.save("EXEMPT_COUNTER",counter);}
 }
}
