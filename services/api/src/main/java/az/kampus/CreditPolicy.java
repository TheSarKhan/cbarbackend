package az.kampus;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static az.kampus.Store.*;

/** Demo checks implement the supplied TT; they do not certify legal compliance. */
@Component
public class CreditPolicy {
    final Store store;
    CreditPolicy(Store store){this.store=store;}

    static long outstanding(List<Map<String,Object>> loans){
        return loans.stream().filter(x->Set.of("ACTIVE","OVERDUE","BAD").contains(str(x,"status")))
            .mapToLong(x->num(x,"outstandingMinor")).sum();
    }

    static long badDebt(List<Map<String,Object>> loans,Instant now){
        long amount=0;
        for(var loan:loans){
            String status=str(loan,"status");
            if(status.equals("WRITTEN_OFF")){
                String date=str(loan,"writtenOffAt");
                if(!date.isBlank()&&!Instant.parse(date).isBefore(now.minus(Duration.ofDays(365))))
                    amount=Math.addExact(amount,num(loan,loan.containsKey("writtenOffMinor")?"writtenOffMinor":"principalMinor"));
            }else if(Set.of("ACTIVE","OVERDUE","BAD").contains(status)){
                String due=str(loan,"dueAt");
                if(status.equals("BAD")||(!due.isBlank()&&Instant.parse(due).plus(Duration.ofDays(60)).isBefore(now)))
                    amount=Math.addExact(amount,num(loan,"outstandingMinor"));
            }
        }
        return amount;
    }

    static Map<String,Object> calculate(long capital,List<Map<String,Object>> loans,Instant now){
        long portfolio=outstanding(loans),bad=badDebt(loans,now);
        return m("capitalMinor",capital,"portfolioMinor",portfolio,"limitMinor",capital/10,
            "portfolioPercent",percent(portfolio,capital),"withinLimit",portfolio<=capital/10,
            "badDebtMinor",bad,"badDebtLimitMinor",capital/100,"badLoanRatioPercent",percent(bad,capital),
            "maxBadLoanPercent",1,"withinBadLoanLimit",bad<=capital/100,
            "minimumCapitalMet",capital>=75_000_000);
    }

    static BigDecimal percent(long amount,long capital){
        Problem.require(capital>0,"INVALID_CAPITAL","Kapital müsbət olmalıdır.");
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(capital),4,RoundingMode.HALF_UP);
    }

    Map<String,Object> report(){
        return calculate(num(store.get("SETTING","total_capital_minor"),"value"),store.list("BNPL",null),Instant.now());
    }

    Map<String,Object> scoring(String uid){
        var incoming=store.list("TX",uid).stream().filter(x->str(x,"direction").equals("IN")&&Set.of("TOPUP","P2P").contains(str(x,"type"))).toList();
        long activeMonths=incoming.stream().map(x->str(x,"createdAt").substring(0,7)).distinct().count();
        long income=incoming.stream().filter(x->Instant.parse(str(x,"createdAt")).isAfter(Instant.now().minus(Duration.ofDays(90)))).mapToLong(x->num(x,"amountMinor")).sum()/3;
        var loans=store.list("BNPL",uid);
        boolean overdue=loans.stream().anyMatch(x->Set.of("OVERDUE","BAD").contains(str(x,"status"))||str(x,"status").equals("ACTIVE")&&Instant.parse(str(x,"dueAt")).isBefore(Instant.now()));
        return m("activeMonths",activeMonths,"averageIncomeMinor",income,"hasOverdueDebt",overdue,"repaidBnplCount",loans.stream().filter(x->str(x,"status").equals("REPAID")).count());
    }

    Map<String,Object> eligibility(String uid){
        var row=store.db.queryForMap("SELECT created_at,birth_date FROM users WHERE id=?",uid);
        boolean oldEnough=Instant.parse(str(row,"created_at")).plus(Duration.ofDays(14)).isBefore(Instant.now());
        boolean adult=!java.time.LocalDate.parse(str(row,"birth_date")).isAfter(java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusYears(18));
        boolean kyc=!store.list("KYC",uid).isEmpty();
        long recent=store.list("TX",uid).stream().filter(x->str(x,"direction").equals("IN")&&Set.of("TOPUP","P2P").contains(str(x,"type"))&&Instant.parse(str(x,"createdAt")).isAfter(Instant.now().minus(Duration.ofDays(30)))).count();
        var loans=store.list("BNPL",uid);
        boolean overdue=loans.stream().anyMatch(x->Set.of("OVERDUE","BAD").contains(str(x,"status"))||str(x,"status").equals("ACTIVE")&&Instant.parse(str(x,"dueAt")).isBefore(Instant.now()));
        var reasons=new ArrayList<String>();
        if(!oldEnough)reasons.add("ACCOUNT_AGE_14_DAYS");if(!adult)reasons.add("AGE_18_REQUIRED");if(!kyc)reasons.add("KYC_REQUIRED");if(recent<2)reasons.add("TWO_RECENT_INCOMING_REQUIRED");if(overdue)reasons.add("OVERDUE_DEBT");
        long available=reasons.isEmpty()?Math.max(0,5000-outstanding(loans)):0;
        return m("eligible",reasons.isEmpty(),"availableMinor",available,"reasons",reasons,"autoPay",true,"recentIncomingCount",recent);
    }

    void requireNewLoan(long amount){
        var report=report();
        Problem.require(yes(report,"minimumCapitalMet")&&amount<=num(report,"limitMinor")-num(report,"portfolioMinor"),
            "CAPITAL_LIMIT","Kapital və ya portfel limiti uyğun deyil.");
        Problem.require(yes(report,"withinBadLoanLimit"),"BAD_DEBT_LIMIT","Ümidsiz və silinmiş kreditlərin məbləği kapitalın 1%-ini aşır.");
    }
}
