package az.kampus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static az.kampus.Store.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:payment_regressions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","kampus.debt-delay-ms=3600000","kampus.insights-scan-initial-ms=3600000"},webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Transactional
class PaymentRegressionTests {
 @Autowired Engine engine;@Autowired Store s;@Autowired Ledger ledger;@Autowired OpenBanking openBanking;@Autowired Tokens tokens;@Autowired InsightsService insights;
 @MockitoBean Simulator simulator;
 Auth.User user(){return new Auth.User("u1","Aytən Məmmədova","+994501000001","USER","demo-device",6);}
 @Test void knownTokenizedFinResolvesToTheSameWalletAsPhone(){
  var phone=engine.resolve("+994501000002");var fin=engine.resolve("7MR2345");
  var publicResolution=(Map<String,Object>)engine.read(user(),"transfers/resolve","7MR2345");assertFalse(publicResolution.containsKey("recipientPhone"));
  assertEquals(phone.get("recipientId"),fin.get("recipientId"));assertEquals("INTERNAL",fin.get("channel"));assertEquals("Murad Əliyev",fin.get("maskedName"));
 }
 @Test void finTransferCreditsRecipientExactlyOnceAndReconciles(){
  long before=ledger.available("wallet:u1"),other=ledger.available("wallet:u2");
  var body=m("phone","7MR2345","amountMinor",1000,"recipientToken",tokens.issue("u1","P2P_RECIPIENT",m("phone","7MR2345"),300));
  var transfer=(Map<String,Object>)engine.write(user(),"transfers",body,"regression-fin-transfer",null);
  assertEquals(before-1000,ledger.available("wallet:u1"));assertEquals(other,ledger.available("wallet:u2"));
  assertFalse(transfer.containsKey("recipientPhone"));
  assertFalse(((Map<?,?>)engine.read(user(),"transfers/"+str(transfer,"id"),"")).containsKey("recipientPhone"));
  var stored=s.get("TRANSFER",str(transfer,"id"));stored.put("executeAt",0L);s.save("TRANSFER",stored);engine.processTransfers();engine.processTransfers();
  assertEquals("COMPLETED",s.get("TRANSFER",str(transfer,"id")).get("status"));
  assertEquals(before-1000,ledger.available("wallet:u1"));assertEquals(other+1000,ledger.available("wallet:u2"));assertEquals(0,num(ledger.reconcile(),"differenceMinor"));
 }
 @Test void ownFinCannotBeUsedToTransferToSelf(){
  var body=m("phone","7AZ1234","amountMinor",1000);
  assertEquals("SELF_TRANSFER",assertThrows(Problem.class,()->engine.write(user(),"transfers",body,"regression-self-transfer",null)).code);
 }
 @Test void finCannotBypassAPhoneBlacklist(){
  s.add("BLACKLIST","SYSTEM",m("target","+994501000002"));
  long before=ledger.available("wallet:u1");
  assertEquals("BLACKLISTED_RECIPIENT",assertThrows(Problem.class,()->engine.write(user(),"transfers",m("phone","7MR2345","amountMinor",1000),"regression-blacklist-fin",null)).code);
  assertEquals(before,ledger.available("wallet:u1"));
 }
 @Test void seededHistoryDoesNotProduceSpendingOrStaleAlerts(){
  assertFalse(s.list("TX","u1").isEmpty());assertTrue(engine.customerTransactions("u1").stream().noneMatch(t->yes(t,"demoHistory")));
  var synthetic=s.list("TX","u1").stream().filter(t->yes(t,"demoHistory")&&"OUT".equals(str(t,"direction"))).findFirst().orElseThrow();
  s.add("ANOMALY","u1",m("txId",synthetic.get("id"),"at",now(),"status","OPEN"));
  assertEquals(0,num(engine.analytics("u1"),"totalMinor"));
  var view=insights.view(user(),null);assertEquals(0,num(view,"spendingCount"));assertTrue(((List<?>)view.get("anomalies")).isEmpty());
  assertEquals(0,num(insights.brief("u1"),"openAnomalies"));
 }
 @Test void generatedHistoryCannotTriggerOrSuppressRealBudgetWarnings(){
  s.add("BUDGET","u1",m("category","Yemək","limitMinor",1000));
  assertEquals(0,openBanking.monthSpend("u1",java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().substring(0,7)).getOrDefault("Yemək",0L));
  engine.tx("u1","QR","Café",850,"OUT","Yemək");
  assertTrue(s.list("NOTIFICATION","u1").stream().anyMatch(n->str(n,"title").contains("80%")));
  assertEquals(850,openBanking.monthSpend("u1",java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().substring(0,7)).getOrDefault("Yemək",0L));
 }
 @Test void analyticsTotalsMatchActualCompletedPaymentsOnly(){
  engine.tx("u1","QR","Café",1500,"OUT","Yemək");
  s.add("TX","u1",m("amountMinor",9000,"direction","OUT","category","Yemək","status","FAILED","name","Declined"));
  s.add("TX","u2",m("amountMinor",7000,"direction","OUT","category","Yemək","status","COMPLETED","name","Other person"));
  assertEquals(1500,num(engine.analytics("u1"),"totalMinor"));
  var view=insights.view(user(),null);assertEquals(1,num(view,"spendingCount"));assertEquals(1500,num((Map<String,Object>)view.get("forecast"),"monthSpentMinor"));
 }
}
