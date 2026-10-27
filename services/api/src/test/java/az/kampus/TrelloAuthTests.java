package az.kampus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static az.kampus.Store.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:trello;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","kampus.debt-delay-ms=3600000"},webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Transactional
class TrelloAuthTests {
 @Autowired Auth auth; @Autowired Store store;
 @MockitoBean Simulator simulator;
 Map<String,Object> identity(boolean verified){
  var otp=auth.otp(m("phone","+994509876543"));
  var body=m("otpId",otp.get("otpId"),"otp",otp.get("demoCode"),"fin","ZX90123","idSeries","AA7654321");
  if(verified)auth.verifyOtp(body);return body;
 }
 @Test void identityCannotBeCheckedBeforeVerifiedSms(){assertEquals("INVALID_OTP",assertThrows(Problem.class,()->auth.validateIdentity(identity(false))).code);}
 @Test void duplicatesAreRejectedBeforeCameraAndFinalRegistration(){
  var body=identity(true);body.put("fin","7AZ1234");
  assertEquals("FIN_EXISTS",assertThrows(Problem.class,()->auth.validateIdentity(body)).code);
  body.put("fin","ZX90123");body.put("idSeries","AZE10000001");
  assertEquals("ID_SERIES_EXISTS",assertThrows(Problem.class,()->auth.validateIdentity(body)).code);
 }
 @Test void allSupportedDocumentPrefixesPassEarlyValidation(){
  var body=identity(true);
  for(String prefix:List.of("AA","AZE","AB","MY","DY","MYI","DYI")){
   body.put("idSeries",prefix+(prefix.equals("AZE")?"87654321":"7654321"));assertEquals(true,auth.validateIdentity(body).get("valid"));
  }
 }
 @Test void usedAndExpiredCodesCannotCheckIdentity(){
  var body=identity(true);var otp=store.get("OTP",str(body,"otpId"));otp.put("used",true);store.save("OTP",otp);
  assertEquals("INVALID_OTP",assertThrows(Problem.class,()->auth.validateIdentity(body)).code);
  otp.put("used",false);otp.put("expiresAt",0);otp.put("registerUntil",0);store.save("OTP",otp);
  assertEquals("INVALID_OTP",assertThrows(Problem.class,()->auth.validateIdentity(body)).code);
 }
 @Test void wrongPinsExposeRemainingAttemptsAndLockAfterThird(){
  store.db.update("UPDATE users SET failed_attempts=0,locked_until=0 WHERE id='u4'");
  String phone=store.db.queryForObject("SELECT phone FROM users WHERE id='u4'",String.class);
  for(int expected=2;expected>=0;expected--){
   var failure=assertThrows(CredentialFailure.class,()->auth.login(m("phone",phone,"pin","999999999","deviceId","trello-tests")));
   assertEquals(expected,failure.remainingAttempts);assertTrue(failure.getMessage().contains(expected==0?"bloklanıb":expected+" cəhd"));
   var response=new ProblemHandler().problem(failure);assertEquals(expected,response.getBody().getProperties().get("remainingAttempts"));
  }
  assertEquals(423,assertThrows(Problem.class,()->auth.login(m("phone",phone,"pin","999999999","deviceId","trello-tests"))).status);
 }
 @Test void namesAllowLatinIncludingAzerbaijaniButRejectCyrillic(){
  for(String name:List.of("Əli","Çiçək","İlqar","Şəms","Ömər","José","Anne-Marie"))assertTrue(name.matches(Auth.NAME_PATTERN),name);
  for(String name:List.of("Иван","Aли","محمد","李明"))assertFalse(name.matches(Auth.NAME_PATTERN),name);
 }
 @Test void namesHaveSameFiftyCharacterLimitAsMobile(){assertTrue("A".repeat(50).matches(Auth.NAME_PATTERN));assertFalse("A".repeat(51).matches(Auth.NAME_PATTERN));}
}
