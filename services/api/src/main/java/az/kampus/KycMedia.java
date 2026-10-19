package az.kampus;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import static az.kampus.Store.*;

/** eKYC photos live in the database only as AES-256-GCM ciphertext; the key comes from the environment. */
@Component public class KycMedia {
 static final String LOCAL_KEY="kampus-local-kyc-media-key-not-for-hosted";
 final JdbcTemplate db; final Store s; final SecretKeySpec key; final SecureRandom random=new SecureRandom();
 KycMedia(JdbcTemplate db,Store s,Environment env){
  this.db=db;this.s=s;
  String secret=env.getProperty("kampus.kyc-media-key","");
  if(secret.isBlank())secret=LOCAL_KEY;
  try{key=new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)),"AES");}catch(Exception e){throw new IllegalStateException(e);}
 }
 /** Accepts a data URL or bare base64 JPEG and rejects anything that does not decode as a plausible selfie. */
 static byte[] parseSelfie(String raw){
  Problem.require(!raw.isBlank(),"SELFIE_REQUIRED","Kamera ilə selfi çəkilməlidir.");
  String b64=raw.startsWith("data:")?raw.substring(raw.indexOf(',')+1):raw;
  byte[] bytes;try{bytes=Base64.getDecoder().decode(b64.trim());}catch(IllegalArgumentException e){throw new Problem(422,"INVALID_SELFIE","Selfi oxunmadı. Yenidən çəkin.");}
  Problem.require(bytes.length>=2_000&&bytes.length<=400_000,"INVALID_SELFIE","Selfinin ölçüsü uyğun deyil. Yenidən çəkin.");
  Problem.require((bytes[0]&0xFF)==0xFF&&(bytes[1]&0xFF)==0xD8&&(bytes[2]&0xFF)==0xFF,"INVALID_SELFIE","Selfi JPEG formatında olmalıdır.");
  try{var img=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));Problem.require(img!=null&&img.getWidth()>=160&&img.getHeight()>=160&&img.getWidth()<=2048&&img.getHeight()<=2048,"INVALID_SELFIE","Selfinin ölçüsü uyğun deyil. Yenidən çəkin.");}
  catch(java.io.IOException e){throw new Problem(422,"INVALID_SELFIE","Selfi oxunmadı. Yenidən çəkin.");}
  return bytes;
 }
 static String sha256(byte[] v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v));}catch(Exception e){throw new IllegalStateException(e);}}
 String store(String userId,String kind,String mime,byte[] plain){
  String id=Store.id();
  db.update("INSERT INTO kyc_media(id,user_id,kind,mime,sha256,size_bytes,data,created_at) VALUES(?,?,?,?,?,?,?,?)",id,userId,kind,mime,sha256(plain),plain.length,seal(userId,kind,plain),Store.now());
  return id;
 }
 /** Admin/compliance read of the newest selfie; every view is audited. */
 Map<String,Object> selfie(String userId,String actor){
  var rows=db.queryForList("SELECT id,mime,sha256,data,created_at FROM kyc_media WHERE user_id=? AND kind='SELFIE' ORDER BY created_at DESC",userId);
  if(rows.isEmpty())throw new Problem(404,"NOT_FOUND","Bu istifadəçi üçün foto tapılmadı.");
  var r=rows.getFirst();
  byte[] plain=open(userId,"SELFIE",str(r,"data"));
  s.audit(actor,"KYC_SELFIE_VIEWED",m("userId",userId,"mediaId",r.get("id")));
  return m("mime",r.get("mime"),"sha256",r.get("sha256"),"createdAt",r.get("created_at"),"dataBase64",Base64.getEncoder().encodeToString(plain));
 }
 String seal(String userId,String kind,byte[] plain){
  try{
   byte[] iv=new byte[12];random.nextBytes(iv);
   var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));
   c.updateAAD((userId+":"+kind).getBytes(StandardCharsets.UTF_8));
   byte[] sealed=c.doFinal(plain),out=new byte[iv.length+sealed.length];
   System.arraycopy(iv,0,out,0,iv.length);System.arraycopy(sealed,0,out,iv.length,sealed.length);
   return Base64.getEncoder().encodeToString(out);
  }catch(Exception e){throw new IllegalStateException("KYC media encryption failed",e);}
 }
 byte[] open(String userId,String kind,String data){
  try{
   byte[] in=Base64.getDecoder().decode(data);
   var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,in,0,12));
   c.updateAAD((userId+":"+kind).getBytes(StandardCharsets.UTF_8));
   return c.doFinal(in,12,in.length-12);
  }catch(Exception e){throw new Problem(500,"KYC_MEDIA_UNREADABLE","Foto oxuna bilmədi.");}
 }
}
