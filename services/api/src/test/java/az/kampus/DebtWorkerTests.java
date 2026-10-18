package az.kampus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static az.kampus.Store.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:debts;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","kampus.debt-delay-ms=3600000"},webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Transactional
class DebtWorkerTests {
 @Autowired Engine engine;@Autowired Store s;@Autowired Ledger l;@Autowired DebtWorker debts;@Autowired CreditPolicy policy;
 @MockitoBean Simulator simulator;
 Map<String,Object> loan(Instant due){
  long amount=1200;
  l.post("BNPL_FUND","Test BNPL funding","CUSTOMER_FUNDS_BANK","OWN_FUNDS_BANK",amount);
  l.post("QR_PAYMENT","Test purchase","CREDIT_RECEIVABLE","merchant:m1",amount);
  return s.add("BNPL","u1",m("principalMinor",amount,"outstandingMinor",amount,"dueAt",due.toString(),"status","ACTIVE","autoPay",true));
 }
 @Test void autoRepaymentIsAtomicAndRunsOnce(){
  var loan=loan(Instant.now().minusSeconds(1));long before=l.available("wallet:u1");
  debts.processAt(Instant.now());debts.processAt(Instant.now());
  assertEquals(before-1200,l.available("wallet:u1"));assertEquals("REPAID",str(s.get("BNPL",str(loan,"id")),"status"));
  assertEquals(0,num(s.get("BNPL",str(loan,"id")),"outstandingMinor"));assertEquals(0,num(l.reconcile(),"differenceMinor"));
 }
 @Test void heldFundsAreNotUsedAndOverdueLoansCanBeRepaid(){
  var loan=loan(Instant.now().minus(Duration.ofDays(1)));String hold=l.hold("wallet:u1",l.available("wallet:u1"));
  debts.processAt(Instant.now());loan=s.get("BNPL",str(loan,"id"));assertEquals("OVERDUE",str(loan,"status"));
  assertFalse(yes(policy.eligibility("u1"),"eligible"));l.release(hold);debts.repayBnpl("u1",loan,false);
  assertEquals("REPAID",str(s.get("BNPL",str(loan,"id")),"status"));assertEquals(0,num(l.reconcile(),"differenceMinor"));
 }
 @Test void badDebtStatusAppearsAfterSixtyOverdueDays(){
  var loan=loan(Instant.now().minus(Duration.ofDays(61)));loan.put("autoPay",false);s.save("BNPL",loan);
  debts.processAt(Instant.now());assertEquals("BAD",str(s.get("BNPL",str(loan,"id")),"status"));assertEquals(1200,num(policy.report(),"badDebtMinor"));
 }
 @Test void youngAccountsAndInsufficientRecentActivityAreIneligible(){
  assertTrue(yes(policy.eligibility("u1"),"eligible"));
  s.db.update("UPDATE users SET created_at=? WHERE id='u1'",now());assertFalse(yes(policy.eligibility("u1"),"eligible"));
  s.db.update("UPDATE users SET created_at='2026-01-01T00:00:00Z' WHERE id='u1'");s.db.update("DELETE FROM records WHERE kind='TX' AND owner_id='u1'");
  assertFalse(yes(policy.eligibility("u1"),"eligible"));
 }
 @Test void installmentsBecomeOverdueThenAutopayAfterFundsAreAvailable(){
  var loan=s.add("PARTNER_LOAN","u1",m("partner","Test lender","outstandingMinor",1500,"status","ACTIVE","autoPay",true));
  var inst=s.add("INSTALLMENT","u1",m("loanId",loan.get("id"),"amountMinor",1500,"number",1,"dueDate",LocalDate.now(ZoneOffset.UTC).minusDays(1).toString(),"status","SCHEDULED"));
  String hold=l.hold("wallet:u1",l.available("wallet:u1"));debts.processAt(Instant.now());assertEquals("OVERDUE",str(s.get("INSTALLMENT",str(inst,"id")),"status"));
  l.release(hold);long before=l.available("wallet:u1");debts.processAt(Instant.now());debts.processAt(Instant.now());
  assertEquals(before-1500,l.available("wallet:u1"));assertEquals("REPAID",str(s.get("PARTNER_LOAN",str(loan,"id")),"status"));assertEquals(0,num(l.reconcile(),"differenceMinor"));
 }
 @Test void cardSpendingResetsOncePerCalendarMonth(){
  var card=s.get("CARD","card-demo");card.put("spentMonth","2020-01");card.put("spentMinor",3400);s.save("CARD",card);
  var admin=new Auth.User("u5","Admin","+994501000005","ADMIN","demo-device");
  var first=(Map<?,?>)engine.write(admin,"admin/sim/card-auth",m("cardId","card-demo","merchantName","Cursor","amountMinor",100),"monthly-reset-one",null);
  assertEquals("APPROVED",first.get("decision"));assertEquals(100,num(s.get("CARD","card-demo"),"spentMinor"));
  engine.write(admin,"admin/sim/card-auth",m("cardId","card-demo","merchantName","Cursor","amountMinor",100),"monthly-reset-two",null);
  assertEquals(200,num(s.get("CARD","card-demo"),"spentMinor"));
 }
 @Test void scoringUsesLedgerHistoryInsteadOfFixedIncome(){
  var scoring=policy.scoring("u1");assertEquals(3,num(scoring,"activeMonths"));assertEquals(52480/3,num(scoring,"averageIncomeMinor"));
  s.db.update("DELETE FROM records WHERE kind='TX' AND owner_id='u1'");
  assertEquals(0,num(policy.scoring("u1"),"averageIncomeMinor"));assertEquals(0,num(policy.scoring("u1"),"activeMonths"));
 }

}
