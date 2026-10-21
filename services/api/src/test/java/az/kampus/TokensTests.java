package az.kampus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TokensTests {
 JdbcTemplate db; Tokens tokens;
 @BeforeEach void setUp(){
  var ds=new DriverManagerDataSource("jdbc:h2:mem:tokens-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
  db=new JdbcTemplate(ds);
  db.execute("CREATE TABLE payment_tokens (token_hash VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64) NOT NULL, scope VARCHAR(32) NOT NULL, binding TEXT NOT NULL, expires_at BIGINT NOT NULL, used_at BIGINT)");
  tokens=new Tokens(db,new Store(db,new ObjectMapper()),new MockEnvironment());
 }
 static Map<String,Object> bind(String merchant){var m=new LinkedHashMap<String,Object>();m.put("merchantId",merchant);return m;}

 @Test void aTokenIsSpentOnceAndOnlyItsHashIsStored(){
  String token=tokens.issue("u1","QR_PAY",bind("m1"),60);
  assertTrue(token.startsWith("pt_"));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM payment_tokens WHERE token_hash=?",Integer.class,token),"the clear token must not be stored");
  tokens.consume("u1",token,"QR_PAY",bind("m1"));
  var again=assertThrows(Problem.class,()->tokens.consume("u1",token,"QR_PAY",bind("m1")));
  assertEquals("TOKEN_USED",again.code);
 }
 @Test void aTokenBelongsToOnePersonOneScopeAndOneSetOfDetails(){
  String token=tokens.issue("u1","QR_PAY",bind("m1"),60);
  assertEquals("TOKEN_INVALID",assertThrows(Problem.class,()->tokens.consume("u2",token,"QR_PAY",bind("m1"))).code);
  assertEquals("TOKEN_INVALID",assertThrows(Problem.class,()->tokens.consume("u1",token,"TRANSFER",bind("m1"))).code);
  assertEquals("TOKEN_MISMATCH",assertThrows(Problem.class,()->tokens.consume("u1",token,"QR_PAY",bind("m2"))).code);
  tokens.consume("u1",token,"QR_PAY",bind("m1")); // none of the failed attempts spent it
 }
 @Test void expiredAndUnknownTokensAreRefused(){
  String token=tokens.issue("u1","QR_PAY",bind("m1"),-1);
  assertEquals("TOKEN_EXPIRED",assertThrows(Problem.class,()->tokens.consume("u1",token,"QR_PAY",bind("m1"))).code);
  assertEquals("TOKEN_INVALID",assertThrows(Problem.class,()->tokens.consume("u1","pt_unknown","QR_PAY",bind("m1"))).code);
  assertEquals("TOKEN_INVALID",assertThrows(Problem.class,()->tokens.consume("u1",null,"QR_PAY",bind("m1"))).code);
 }
 @Test void identifierTokensAreStableCaseInsensitiveAndHideTheValue(){
  String a=tokens.identifier("fin","7az1234");
  assertEquals(a,tokens.identifier("fin","7AZ1234"));
  assertNotEquals(a,tokens.identifier("series","7AZ1234"),"the kind is part of the token");
  assertFalse(a.contains("7AZ1234"));
  assertEquals("7••••34",Tokens.mask("7AZ1234"));
  assertEquals("•••",Tokens.mask("AB"));
 }
 @Test void protectingAKycRecordRemovesThePlainIdentifiers(){
  var kyc=new LinkedHashMap<String,Object>(Map.of("fin","7AZ1234","idSeries","AZE12345678","name","x"));
  assertTrue(tokens.protect(kyc));
  assertFalse(kyc.containsKey("fin"));assertFalse(kyc.containsKey("idSeries"));
  assertTrue(tokens.has(kyc,"fin","fin","7az1234"));
  assertFalse(tokens.has(kyc,"fin","fin","7AZ9999"));
  assertFalse(tokens.protect(kyc),"protecting twice changes nothing");
  assertTrue(tokens.has(Map.of("fin","7AZ1234"),"fin","fin","7AZ1234"),"records not yet migrated still match");
 }
}
