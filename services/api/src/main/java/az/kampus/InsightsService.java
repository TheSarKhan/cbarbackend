package az.kampus;

import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import static az.kampus.Store.*;

/** Connects the analytics to the customer's data: consent, remembered answers about unusual payments, budgets and notifications. */
@Service
public class InsightsService {
 interface Notifier{void send(String user,String title,String body);}
 static final int MIN_GROUP=3;
 final Store s;final Ledger l;
 InsightsService(Store s,Ledger l){this.s=s;this.l=l;}

 boolean enabled(String uid){return s.list("PREF",uid).stream().noneMatch(p->Boolean.FALSE.equals(p.get("analytics")));}

 private record Run(List<Insights.Spend> spends,List<Insights.Recurring> recurring,List<Insights.Anomaly> anomalies,Instant now){}

 private Run analyse(String uid){
  Instant now=Instant.now();
  var spends=Insights.spending(s.list("TX",uid),s.list("OB_TX",uid));
  var recurring=Insights.recurring(spends,now);
  return new Run(spends,recurring,Insights.anomalies(spends,now,Set.of(),Set.of()),now);
 }

 /** Records every unusual payment once and tells the customer; returns the records that are still waiting for an answer. */
 private List<Map<String,Object>> register(String uid,Run run,Notifier notifier){
  var known=new HashSet<String>();
  for(var a:s.list("ANOMALY",uid))known.add(str(a,"txId"));
  for(var a:run.anomalies()){
   if(known.contains(a.txId()))continue;
   var record=s.add("ANOMALY",uid,m("txId",a.txId(),"merchantKey",a.key(),"merchant",a.merchant(),"category",a.category(),"amountMinor",a.amount(),"reason",a.reason(),
    "score",a.score(),"severity",a.severity(),"explanation",a.explanation(),"at",a.at().toString(),"status","OPEN"));
   known.add(a.txId());
   if(notifier!=null)notifier.send(uid,"Qeyri-adi ödəniş",a.explanation()+" Bunlar sənsən? Analitika ekranında cavab ver.");
   s.audit(uid,"ANOMALY_DETECTED",m("anomalyId",record.get("id"),"reason",a.reason(),"score",a.score()));
  }
  Instant since=run.now().minus(Duration.ofDays(30));
  return s.list("ANOMALY",uid).stream().filter(a->"OPEN".equals(str(a,"status"))&&Instant.parse(str(a,"at")).isAfter(since))
   .sorted(Comparator.comparing((Map<String,Object> a)->str(a,"at")).reversed()).toList();
 }

 private Map<String,Long> budgets(String uid){
  var out=new LinkedHashMap<String,Long>();
  for(var b:s.list("BUDGET",uid))out.put(str(b,"category"),num(b,"limitMinor"));
  return out;
 }

 /** Everything the analytics screen shows. */
 Map<String,Object> view(Auth.User u,Notifier notifier){
  String uid=u.id();
  if(!enabled(uid))return m("enabled",false);
  var run=analyse(uid);
  var open=register(uid,run,notifier);
  var budgets=budgets(uid);
  var forecast=Insights.forecast(run.spends(),run.recurring(),run.now(),l.available("wallet:"+uid));
  var breakdown=Insights.breakdown(run.spends(),run.now());
  var risks=Insights.budgetRisks(run.spends(),run.recurring(),budgets,run.now());
  var suggestions=Insights.budgetSuggestions(run.spends(),run.now(),budgets).stream()
   .filter(x->num(x,"suggestedMinor")!=num(x,"currentMinor")).toList();
  var openViews=open.stream().map(a->m("id",a.get("id"),"txId",a.get("txId"),"merchant",a.get("merchant"),"category",a.get("category"),"amountMinor",a.get("amountMinor"),
   "reason",a.get("reason"),"severity",a.get("severity"),"explanation",a.get("explanation"),"at",a.get("at"))).toList();
  var openAnomalies=run.anomalies().stream().filter(a->open.stream().anyMatch(o->str(o,"txId").equals(a.txId()))).toList();
  return m("enabled",true,"generatedAt",run.now().toString(),"forecast",forecast,"breakdown",breakdown,
   "recurring",run.recurring().stream().map(Insights.Recurring::view).toList(),"anomalies",openViews,"budgetRisks",risks,"budgetSuggestions",suggestions,
   "cards",Insights.cards(forecast,breakdown,run.recurring(),openAnomalies,risks,suggestions));
 }

 /** The short version on the Home card, and the per-payment flags shown in the history. Reads only; nothing is written. */
 Map<String,Object> brief(String uid){
  if(!enabled(uid))return m("enabled",false);
  var run=analyse(uid);
  var forecast=Insights.forecast(run.spends(),run.recurring(),run.now(),null);
  var open=s.list("ANOMALY",uid).stream().filter(a->"OPEN".equals(str(a,"status"))).count();
  var recurringKeys=run.recurring().stream().map(Insights.Recurring::key).collect(Collectors.toSet());
  var flags=new LinkedHashMap<String,String>();
  for(var sp:run.spends())if(recurringKeys.contains(sp.key()))flags.put(sp.id(),"RECURRING");
  for(var a:s.list("ANOMALY",uid))flags.put(str(a,"txId"),"ANOMALY");
  return m("enabled",true,"monthSpentMinor",forecast.get("monthSpentMinor"),"projectedMinor",forecast.get("projectedMinor"),"vsLastMonthPct",forecast.get("vsLastMonthPct"),
   "openAnomalies",open,"recurringCount",run.recurring().size(),"flags",flags);
 }

 Map<String,Object> setConsent(String uid,boolean on){
  var pref=s.list("PREF",uid).stream().findFirst().orElse(null);
  if(pref==null)pref=s.add("PREF",uid,m("analytics",on));else{pref.put("analytics",on);s.save("PREF",pref);}
  s.audit(uid,on?"ANALYTICS_ENABLED":"ANALYTICS_DISABLED",m());
  return m("enabled",on);
 }

 /** Applies the suggested limits for the categories asked for. The numbers are recomputed here, not taken from the client. */
 List<Map<String,Object>> applyBudgets(String uid,List<?> categories){
  var run=analyse(uid);
  var suggestions=Insights.budgetSuggestions(run.spends(),run.now(),budgets(uid));
  var applied=new ArrayList<Map<String,Object>>();
  for(var suggestion:suggestions){
   String category=str(suggestion,"category");
   if(!categories.isEmpty()&&categories.stream().noneMatch(c->category.equals(String.valueOf(c))))continue;
   long limit=num(suggestion,"suggestedMinor");
   var existing=s.list("BUDGET",uid).stream().filter(x->category.equals(str(x,"category"))).findFirst().orElse(null);
   if(existing!=null){existing.put("limitMinor",limit);s.save("BUDGET",existing);applied.add(existing);}
   else applied.add(s.add("BUDGET",uid,m("category",category,"limitMinor",limit)));
  }
  s.audit(uid,"BUDGETS_SUGGESTED_APPLIED",m("count",applied.size()));
  return applied;
 }

 /** Checks every customer who allows it, so unusual payments are reported without anyone opening the screen. */
 void scan(Notifier notifier){
  for(var u:s.db.queryForList("SELECT id FROM users WHERE role='USER'")){
   String uid=u.get("id").toString();
   if(!enabled(uid))continue;
   try{register(uid,analyse(uid),notifier);}catch(RuntimeException e){/* one customer's odd data must not stop the rest */}
  }
 }

 /** Anonymous totals for the admin: only customers who allow it, and groups smaller than three people are not shown. */
 Map<String,Object> adminOverview(){
  var per=new LinkedHashMap<String,List<Insights.Spend>>();
  int recurringUsers=0,anomalies=0;
  for(var u:s.db.queryForList("SELECT id FROM users WHERE role='USER'")){
   String uid=u.get("id").toString();
   if(!enabled(uid))continue;
   var run=analyse(uid);
   if(run.spends().isEmpty())continue;
   per.put(uid,run.spends());
   if(!run.recurring().isEmpty())recurringUsers++;
   anomalies+=run.anomalies().size();
  }
  var overview=new LinkedHashMap<>(Insights.aggregate(per,Instant.now(),MIN_GROUP,anomalies,recurringUsers));
  overview.put("optedOut",s.list("PREF",null).stream().filter(p->Boolean.FALSE.equals(p.get("analytics"))).count());
  return overview;
 }
}
