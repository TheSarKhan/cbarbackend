package az.kampus;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class KycStepUpTests {
 final Auth auth=new Auth(null,null,null,null,50_000);
 static Map<String,Object> payload(String key,Object amount){return Map.of("action","transfers","data",Map.of(key,amount,"phone","+994501000002"));}
 @Test void amountsFromTheConfirmationPayloadDecideWhetherAFaceCheckIsNeeded(){
  assertFalse(auth.needsKyc(payload("amountMinor",49_999)));
  assertTrue(auth.needsKyc(payload("amountMinor",50_000)));
  assertTrue(auth.needsKyc(payload("amountMinor","75000")),"numbers sent as text still count");
  assertTrue(auth.needsKyc(payload("totalMinor",120_000)),"card transfers carry totalMinor");
 }
 @Test void actionsWithoutAnAmountNeverNeedAFaceCheck(){
  assertFalse(auth.needsKyc(Map.of("action","cards/1/reveal","data",Map.of("cardId","1"))));
  assertFalse(auth.needsKyc(null));
  assertFalse(auth.needsKyc("not a payload"));
  assertFalse(auth.needsKyc(payload("amountMinor","garbage")));
 }
}
