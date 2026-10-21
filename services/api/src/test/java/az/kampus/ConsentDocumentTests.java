package az.kampus;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConsentDocumentTests {
 static Map<String,Object> consent(){return Map.of("id","c-1","bankName","Kapital Bank","months",3,"monthlyLimitMinor",30_000);}
 static List<Map<String,Object>> cards(){return List.of(Map.of("name","Debet kart · VISA","masked","•••• 2261"));}
 static final Instant FROM=Instant.parse("2026-10-22T10:00:00Z");

 @Test void theDocumentStatesTheTermsTheCustomerChose(){
  var doc=ConsentDocument.build(consent(),"Aytən Məmmədova","7••••34","+994501000001",cards(),FROM,null);
  String text=doc.toString();
  assertTrue(text.contains("3 ay (22.10.2026 – 20.01.2027)"),text);
  assertTrue(text.contains("300.00 AZN"));
  assertTrue(text.contains("Debet kart · VISA · •••• 2261"));
  assertEquals(Boolean.FALSE,doc.get("signed"));
 }
 @Test void aSignatureDoesNotChangeWhatTheSignatureCovers(){
  var unsigned=ConsentDocument.build(consent(),"A","7••••34","+994501000001",cards(),FROM,null);
  var signed=ConsentDocument.build(consent(),"A","7••••34","+994501000001",cards(),FROM,Map.of("contractId","CNT-1","signatureHash","h","signedAt","22.10.2026 10:05","method","SİMA İmza"));
  assertEquals(ConsentDocument.contentHash(unsigned),ConsentDocument.contentHash(signed));
  var otherTerms=ConsentDocument.build(Map.of("id","c-1","bankName","Kapital Bank","months",3,"monthlyLimitMinor",40_000),"A","7••••34","+994501000001",cards(),FROM,null);
  assertNotEquals(ConsentDocument.contentHash(unsigned),ConsentDocument.contentHash(otherTerms),"a different limit is a different agreement");
 }
 @Test void thePdfIsRealAndKeepsAzerbaijaniLetters(){
  var doc=ConsentDocument.build(consent(),"Əli Həsənov","7••••34","+994501000001",cards(),FROM,Map.of("contractId","CNT-1","signatureHash","h","signedAt","22.10.2026 10:05","method","SİMA İmza"));
  byte[] pdf=ConsentDocument.pdf(doc,"Kapital Bank");
  assertEquals("%PDF",new String(pdf,0,4));
  assertTrue(pdf.length>20_000,"the font is embedded");
 }
}
