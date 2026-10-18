package az.kampus;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static az.kampus.Store.*;
import static org.junit.jupiter.api.Assertions.*;

class CreditPolicyTests {
    final Instant now=Instant.parse("2026-10-18T12:00:00Z");
    Map<String,Object> loan(String status,long amount,long overdueDays){
        return m("status",status,"outstandingMinor",amount,"principalMinor",amount,"dueAt",now.minus(Duration.ofDays(overdueDays)).toString());
    }
    @Test void usesCapitalAsBadDebtDenominator(){
        var report=CreditPolicy.calculate(75_000_000,List.of(loan("ACTIVE",750_000,61)),now);
        assertEquals(750_000L,report.get("badDebtMinor"));
        assertEquals(1,report.get("maxBadLoanPercent"));
        assertEquals(true,report.get("withinBadLoanLimit"));
        assertEquals(false,CreditPolicy.calculate(75_000_000,List.of(loan("ACTIVE",750_001,61)),now).get("withinBadLoanLimit"));
    }
    @Test void countsOnlyRecentWriteoffsAndNotRepaidLoans(){
        var recent=loan("WRITTEN_OFF",400,0);recent.put("writtenOffAt",now.minus(Duration.ofDays(100)).toString());
        var old=loan("WRITTEN_OFF",900,0);old.put("writtenOffAt",now.minus(Duration.ofDays(366)).toString());
        assertEquals(400,CreditPolicy.badDebt(List.of(recent,old,loan("REPAID",5000,90),loan("ACTIVE",1000,59)),now));
    }
    @Test void overdueAndBadLoansRemainInPortfolio(){
        assertEquals(600,CreditPolicy.outstanding(List.of(loan("ACTIVE",100,0),loan("OVERDUE",200,30),loan("BAD",300,80),loan("REPAID",400,90))));
    }
}
