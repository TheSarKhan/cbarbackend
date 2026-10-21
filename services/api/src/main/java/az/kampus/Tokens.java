package az.kampus;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import static az.kampus.Store.*;

/**
 * Tokenization in two forms.
 * <p>Payment tokens stand in for a payment instruction: random, single use, short lived and bound to the person and the
 * details they confirmed (for a QR payment the merchant and the exact QR). The database keeps only a hash of the token.
 * <p>Identifier tokens stand in for national identifiers (FİN, ID series): a keyed hash lets the system check "has this
 * identifier registered before" without keeping the identifier itself, and a masked form is all that is ever shown.
 */
@Component public class Tokens {
 static final long TTL_QR_SECONDS=180;
 final JdbcTemplate db; final Store s; final SecretKeySpec hmacKey,sealKey; final SecureRandom random=new SecureRandom();
 Tokens(JdbcTemplate db,Store s,Environment env){
  this.db=db;this.s=s;
  String secret=env.getProperty("kampus.kyc-media-key","");
  if(secret.isBlank())secret=KycMedia.LOCAL_KEY;
  try{
   var sha=MessageDigest.getInstance("SHA-256");
   hmacKey=new SecretKeySpec(sha.digest(("kampus-token-key|"+secret).getBytes(StandardCharsets.UTF_8)),"HmacSHA256");
   sealKey=new SecretKeySpec(sha.digest(("kampus-seal-key|"+secret).getBytes(StandardCharsets.UTF_8)),"AES");
  }catch(Exception e){throw new IllegalStateException(e);}
 }

 /** Issues a token for {@code scope}; the binding is what the later payment must match. */
 String issue(String userId,String scope,Map<String,Object> binding,long ttlSeconds){
  byte[] raw=new byte[32];random.nextBytes(raw);
  String token="pt_"+Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  db.update("DELETE FROM payment_tokens WHERE expires_at<?",System.currentTimeMillis()-3_600_000L);
  db.update("INSERT INTO payment_tokens(token_hash,user_id,scope,binding,expires_at) VALUES(?,?,?,?,?)",Auth.sha256(token),userId,scope,s.encode(binding),System.currentTimeMillis()+ttlSeconds*1000);
  return token;
 }

 /** Spends the token. It must belong to this person and scope, be unexpired, unused and match the details; spending is atomic. */
 void consume(String userId,String token,String scope,Map<String,Object> actual){
  Problem.require(token!=null&&token.startsWith("pt_")&&token.length()<=64,"TOKEN_INVALID","Ödəniş sessiyası tapılmadı. QR kodu yenidən skan et.");
  var rows=db.queryForList("SELECT user_id,scope,binding,expires_at,used_at FROM payment_tokens WHERE token_hash=?",Auth.sha256(token));
  Problem.require(!rows.isEmpty(),"TOKEN_INVALID","Ödəniş sessiyası tapılmadı. QR kodu yenidən skan et.");
  var row=rows.getFirst();
  Problem.require(userId.equals(row.get("user_id"))&&scope.equals(row.get("scope")),"TOKEN_INVALID","Ödəniş sessiyası tapılmadı. QR kodu yenidən skan et.");
  if(row.get("used_at")!=null)throw new Problem(409,"TOKEN_USED","Bu ödəniş sessiyası artıq istifadə olunub.");
  if(((Number)row.get("expires_at")).longValue()<System.currentTimeMillis())throw new Problem(410,"TOKEN_EXPIRED","Ödəniş sessiyasının vaxtı bitdi. QR kodu yenidən skan et.");
  var bound=s.decode(Objects.toString(row.get("binding")));
  for(var e:bound.entrySet())Problem.require(Objects.toString(e.getValue(),"").equals(Objects.toString(actual.get(e.getKey()),"")),"TOKEN_MISMATCH","Ödəniş sessiyası bu ödənişə aid deyil. QR kodu yenidən skan et.");
  int spent=db.update("UPDATE payment_tokens SET used_at=? WHERE token_hash=? AND used_at IS NULL",System.currentTimeMillis(),Auth.sha256(token));
  if(spent!=1)throw new Problem(409,"TOKEN_USED","Bu ödəniş sessiyası artıq istifadə olunub.");
 }

 /** Stable, irreversible stand-in for an identifier such as a FİN; the same input always gives the same token. */
 String identifier(String kind,String value){
  try{
   var mac=Mac.getInstance("HmacSHA256");mac.init(hmacKey);
   return "tok_"+kind+"_"+HexFormat.of().formatHex(mac.doFinal((kind+"|"+value.trim().toUpperCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8))).substring(0,32);
  }catch(Exception e){throw new IllegalStateException(e);}
 }

 /** AES-256-GCM sealing for the few places that must hand an identifier to a partner bank; stored only as ciphertext. */
 String seal(String plain){
  try{
   byte[] iv=new byte[12];random.nextBytes(iv);
   var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,sealKey,new GCMParameterSpec(128,iv));
   byte[] ct=cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
   byte[] out=new byte[iv.length+ct.length];System.arraycopy(iv,0,out,0,iv.length);System.arraycopy(ct,0,out,iv.length,ct.length);
   return Base64.getEncoder().encodeToString(out);
  }catch(Exception e){throw new IllegalStateException(e);}
 }
 String unseal(String sealed){
  try{
   byte[] in=Base64.getDecoder().decode(sealed);
   var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,sealKey,new GCMParameterSpec(128,in,0,12));
   return new String(cipher.doFinal(in,12,in.length-12),StandardCharsets.UTF_8);
  }catch(Exception e){throw new IllegalStateException(e);}
 }
 /** The identifier to give a partner bank: the real value when it was sealed at registration, otherwise its token. */
 String reveal(Map<String,Object> kyc,String field){
  String sealed=str(kyc,field+"Enc");
  if(!sealed.isBlank())return unseal(sealed);
  String token=str(kyc,field+"Token");
  return token.isBlank()?str(kyc,field):token;
 }

 /** First and last characters only, e.g. 7AZ1234 -> 7••••34. */
 static String mask(String value){
  String v=value==null?"":value.trim();
  if(v.length()<=3)return "•••";
  return v.charAt(0)+"•".repeat(v.length()-3)+v.substring(v.length()-2);
 }

 /** Replaces the plain FİN and ID series of a KYC record with tokens and masked forms. Returns true when it changed anything. */
 boolean protect(Map<String,Object> kyc){
  boolean changed=false;
  for(String[] f:new String[][]{{"fin","fin"},{"idSeries","series"}}){
   String plain=str(kyc,f[0]);
   if(plain.isBlank())continue;
   kyc.put(f[0]+"Token",identifier(f[1],plain));kyc.put(f[0]+"Masked",mask(plain));kyc.put(f[0]+"Enc",seal(plain));kyc.remove(f[0]);changed=true;
  }
  return changed;
 }

 Map<String,Object> protectedRecord(Map<String,Object> kyc){protect(kyc);return kyc;}

 /** True when the KYC record belongs to this identifier (token match, or a not yet migrated plain value). */
 boolean has(Map<String,Object> kyc,String field,String kind,String value){
  return identifier(kind,value).equals(str(kyc,field+"Token"))||value.equalsIgnoreCase(str(kyc,field));
 }
}
