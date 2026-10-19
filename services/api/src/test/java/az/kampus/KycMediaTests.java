package az.kampus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;
class KycMediaTests {
 static KycMedia media(String key){return new KycMedia(null,null,new MockEnvironment().withProperty("kampus.kyc-media-key",key));}
 static byte[] jpeg(int size)throws Exception{
  var img=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);var random=new Random(3);
  for(int x=0;x<size;x++)for(int y=0;y<size;y++)img.setRGB(x,y,random.nextInt(0xFFFFFF));
  var out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(img,"jpg",out);return out.toByteArray();
 }
 @Test void storedPhotoIsCiphertextAndRoundTrips()throws Exception{
  var m=media("k".repeat(40));byte[] photo=jpeg(200);
  String sealed=m.seal("user-1","SELFIE",photo);
  byte[] raw=Base64.getDecoder().decode(sealed);
  assertFalse(new String(raw,java.nio.charset.StandardCharsets.ISO_8859_1).contains(new String(photo,0,64,java.nio.charset.StandardCharsets.ISO_8859_1)),"JPEG bytes must not appear in the stored value");
  assertArrayEquals(photo,m.open("user-1","SELFIE",sealed));
  assertNotEquals(sealed,m.seal("user-1","SELFIE",photo),"each encryption uses a fresh nonce");
 }
 @Test void photoCannotBeReadWithAnotherKeyUserOrTampering()throws Exception{
  var m=media("k".repeat(40));String sealed=m.seal("user-1","SELFIE",jpeg(200));
  assertThrows(Problem.class,()->media("z".repeat(40)).open("user-1","SELFIE",sealed));
  assertThrows(Problem.class,()->m.open("user-2","SELFIE",sealed));
  byte[] raw=Base64.getDecoder().decode(sealed);raw[raw.length-1]^=1;
  assertThrows(Problem.class,()->m.open("user-1","SELFIE",Base64.getEncoder().encodeToString(raw)));
 }
 @Test void selfieValidationRejectsNonJpegAndTinyImages()throws Exception{
  byte[] ok=jpeg(240);
  assertArrayEquals(ok,KycMedia.parseSelfie("data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(ok)));
  assertEquals(ok.length,KycMedia.parseSelfie(Base64.getEncoder().encodeToString(ok)).length);
  assertThrows(Problem.class,()->KycMedia.parseSelfie(""));
  assertThrows(Problem.class,()->KycMedia.parseSelfie("%%%not-base64%%%"));
  assertThrows(Problem.class,()->KycMedia.parseSelfie(Base64.getEncoder().encodeToString(new byte[5000])));
  assertThrows(Problem.class,()->KycMedia.parseSelfie(Base64.getEncoder().encodeToString(jpeg(40))));
 }
}
