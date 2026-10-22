package az.kampus;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import static az.kampus.Store.*;
import static org.junit.jupiter.api.Assertions.*;

/** The generator plants known subscriptions, bills and unusual payments, so detection quality is measured and not guessed. */
class InsightsTests {
 static final Instant NOW=Instant.parse("2026-10-20T08:00:00Z");
 static final Instant MONTH_END=Instant.parse("2026-10-31T20:00:00Z");

 static List<Insights.Spend> spends(SyntheticSpending.Result r){return Insights.spending(r.txs(),List.of());}

 @Test void merchantNamesAreNormalised(){
  assertEquals(Insights.key("Bravo Supermarket"),Insights.key("BRAVO supermarket 12"));
  assertEquals(Insights.key("Papa John's"),Insights.key("PAPA JOHN'S #4"));
  assertEquals("BOLT",Insights.key("Bolt MMC"));
  assertNotEquals(Insights.key("Bolt"),Insights.key("Uber"));
 }

 @Test void movesBetweenOwnAccountsAndIncomeAreNotSpending(){
  var tx=List.<Map<String,Object>>of(
   m("id","a","direction","OUT","category","Yemək","name","Bravo","amountMinor",1000,"createdAt","2026-10-10T10:00:00Z"),
   m("id","b","direction","IN","category","Gəlir","name","Stipendiya","amountMinor",25000,"createdAt","2026-10-05T10:00:00Z"),
   m("id","c","direction","OUT","category",Insights.INTERNAL,"name","Köçürmə: cari hesabdan","amountMinor",60000,"createdAt","2026-10-06T10:00:00Z"));
  assertEquals(List.of("a"),Insights.spending(tx,List.of()).stream().map(Insights.Spend::id).toList());
 }

 @Test void subscriptionsAndBillsAreFoundAndEverydayShoppingIsNot(){
  int found=0,planted=0,wrong=0;
  for(long seed=1;seed<=30;seed++){
   var r=SyntheticSpending.generate(seed,NOW,6,0,false);
   var keys=Insights.recurring(spends(r),NOW).stream().map(Insights.Recurring::key).collect(Collectors.toSet());
   planted+=r.recurringKeys().size();
   for(var k:r.recurringKeys())if(keys.contains(k))found++;
   for(var k:keys)if(!r.recurringKeys().contains(k))wrong++;
  }
  double recall=found/(double)planted;
  System.out.printf("recurring: recall %.3f, %d false detections over 30 people%n",recall,wrong);
  assertTrue(recall>=0.95,"recall "+recall);
  assertTrue(wrong<=3,"false detections "+wrong);
 }

 @Test void recurringPaymentsKnowTheirNextDate(){
  var r=SyntheticSpending.generate(2,NOW,6,0,false);
  var netflix=Insights.recurring(spends(r),NOW).stream().filter(x->x.key().equals("NETFLIX")).findFirst().orElseThrow();
  assertEquals("MONTHLY",netflix.period());
  assertEquals(1299,netflix.typical());
  assertEquals(LocalDate.of(2026,11,10),netflix.next(),"the same day of the next month");
 }

 @Test void unusualPaymentsAreFoundWithFewFalseAlarms(){
  int people=40,planted=0,hit=0,detected=0,correct=0,falseWithout=0;
  for(long seed=1;seed<=people;seed++){
   var r=SyntheticSpending.generate(seed,NOW,6,3,false);
   var found=Insights.anomalies(spends(r),NOW,Set.of(),Set.of());
   planted+=r.anomalyIds().size();
   var ids=found.stream().map(Insights.Anomaly::txId).collect(Collectors.toSet());
   for(var id:r.anomalyIds())if(ids.contains(id))hit++;
   detected+=found.size();
   for(var f:found)if(r.anomalyIds().contains(f.txId()))correct++;
   falseWithout+=Insights.anomalies(spends(SyntheticSpending.generate(seed,NOW,6,0,false)),NOW,Set.of(),Set.of()).size();
  }
  double recall=hit/(double)planted,precision=detected==0?0:correct/(double)detected,falsePerPerson=falseWithout/(double)people;
  System.out.printf("anomalies: recall %.3f, precision %.3f, %.2f false alarms per person in 3 weeks without planted ones%n",recall,precision,falsePerPerson);
  assertTrue(recall>=0.9,"recall "+recall);
  assertTrue(precision>=0.8,"precision "+precision);
  assertTrue(falsePerPerson<=0.3,"false alarms per person "+falsePerPerson);
 }

 @Test void whatTheCustomerConfirmedIsNotFlaggedAgain(){
  var r=SyntheticSpending.generate(3,NOW,6,3,false);
  var all=Insights.anomalies(spends(r),NOW,Set.of(),Set.of());
  assertFalse(all.isEmpty());
  var first=all.get(0);
  assertTrue(Insights.anomalies(spends(r),NOW,Set.of(first.txId()),Set.of()).stream().noneMatch(a->a.txId().equals(first.txId())));
  assertTrue(Insights.anomalies(spends(r),NOW,Set.of(),Set.of(first.key())).stream().noneMatch(a->a.key().equals(first.key())));
 }

 @Test void monthEndForecastIsCloseAndHonestAboutItsRange(){
  var errors=new ArrayList<Double>();int inside=0,runs=0;
  for(long seed=1;seed<=30;seed++){
   var full=SyntheticSpending.generate(seed,MONTH_END,6,0,false);
   var all=spends(full);
   long truth=all.stream().filter(s->s.day().getMonthValue()==10).mapToLong(Insights.Spend::amount).sum();
   for(var cut:List.of(Instant.parse("2026-10-12T20:00:00Z"),Instant.parse("2026-10-20T20:00:00Z"))){
    var known=all.stream().filter(s->!s.at().isAfter(cut)).toList();
    var f=Insights.forecast(known,Insights.recurring(known,cut),cut,null);
    long projected=num(f,"projectedMinor");
    errors.add(Math.abs(projected-truth)/(double)truth);
    if(num(f,"lowMinor")<=truth&&truth<=num(f,"highMinor"))inside++;
    runs++;
   }
  }
  double median=Insights.median(errors),coverage=inside/(double)runs;
  System.out.printf("forecast: median error %.1f%%, truth inside the stated range %.0f%%%n",median*100,coverage*100);
  assertTrue(median<=0.15,"median error "+median);
  assertTrue(coverage>=0.6,"coverage "+coverage);
 }

 @Test void budgetSuggestionsFollowTheMedianOfRecentMonths(){
  var r=SyntheticSpending.generate(5,NOW,6,0,false);
  var suggestions=Insights.budgetSuggestions(spends(r),NOW,Map.of("Yemək",5000L));
  var food=suggestions.stream().filter(s->"Yemək".equals(s.get("category"))).findFirst().orElseThrow();
  long suggested=num(food,"suggestedMinor");
  assertEquals(0,suggested%500);
  assertEquals(5000,num(food,"currentMinor"));
  assertTrue(suggested>=5000&&suggested<=60000,"suggested "+suggested);
 }

 @Test void budgetRiskRisesWhenTheMonthIsRunningHot(){
  var r=SyntheticSpending.generate(6,NOW,6,0,false);
  var s=spends(r);var rec=Insights.recurring(s,NOW);
  long spent=s.stream().filter(x->x.category().equals("Yemək")&&x.day().getMonthValue()==10).mapToLong(Insights.Spend::amount).sum();
  var tight=Insights.budgetRisks(s,rec,Map.of("Yemək",spent+500),NOW).get(0);
  var roomy=Insights.budgetRisks(s,rec,Map.of("Yemək",spent*10),NOW).get(0);
  assertEquals("HIGH",tight.get("risk"));
  assertEquals("LOW",roomy.get("risk"));
 }

 @Test void friendlyCardsAreWrittenFromTheNumbers(){
  var r=SyntheticSpending.generate(7,NOW,6,3,false);
  var s=spends(r);var rec=Insights.recurring(s,NOW);
  var f=Insights.forecast(s,rec,NOW,40_000L);
  var b=Insights.breakdown(s,NOW);
  var an=Insights.anomalies(s,NOW,Set.of(),Set.of());
  var cards=Insights.cards(f,b,rec,an,Insights.budgetRisks(s,rec,Map.of("Əyləncə",1000L),NOW),Insights.budgetSuggestions(s,NOW,Map.of()));
  assertFalse(cards.isEmpty());
  for(var c:cards){assertFalse(c.get("title").toString().contains("null"),c.toString());assertFalse(c.get("body").toString().contains("null"),c.toString());}
  assertTrue(cards.stream().anyMatch(c->"FORECAST".equals(c.get("kind"))));
  assertTrue(cards.stream().anyMatch(c->"RECURRING".equals(c.get("kind"))));
  assertTrue(cards.stream().anyMatch(c->"ANOMALY".equals(c.get("kind"))));
 }

 @Test void theAdminOverviewHidesSmallGroups(){
  var per=new LinkedHashMap<String,List<Insights.Spend>>();
  for(long seed=1;seed<=4;seed++)per.put("u"+seed,spends(SyntheticSpending.generate(seed,NOW,3,0,false)));
  var two=new LinkedHashMap<>(per);two.keySet().retainAll(List.of("u1","u2"));
  assertFalse((Boolean)Insights.aggregate(two,NOW,3,0,0).get("enoughUsers"));
  assertNull(Insights.aggregate(two,NOW,3,0,0).get("categories"),"nothing is revealed for fewer than three people");
  var four=Insights.aggregate(per,NOW,3,2,4);
  assertTrue((Boolean)four.get("enoughUsers"));
  assertNotNull(four.get("categories"));
 }
}
