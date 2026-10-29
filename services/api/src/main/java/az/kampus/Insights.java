package az.kampus;

import java.time.*;
import java.util.*;
import java.util.stream.*;
import static az.kampus.Store.*;

/**
 * Spending analytics that can be explained to the customer: every number comes from a small, documented statistic.
 * <ul>
 * <li>Recurring payments: the same merchant at a steady interval and a steady amount.</li>
 * <li>Unusual payments: robust z-score (median and MAD of the category's last 120 days), a first-time merchant with a large
 *     amount, and bursts of many payments in a few minutes.</li>
 * <li>Month-end forecast: spend so far + recurring payments still due + the recent daily pace of everything else,
 *     with a band from the day-to-day variation.</li>
 * <li>Budget suggestions: the median of the last full months of a category plus ten percent.</li>
 * </ul>
 * Pure functions on lists of spends, so they are tested with generated data whose patterns are known in advance.
 */
final class Insights {
 private Insights(){}

 static final ZoneOffset LOCAL=ZoneOffset.ofHours(4); // Baku
 static final String INTERNAL="Daxili köçürmə";

 /** One payment that counts as spending. */
 record Spend(String id,Instant at,long amount,String category,String merchant,String key,String type,String source){
  LocalDate day(){return at.atZone(LOCAL).toLocalDate();}
  int hour(){return at.atZone(LOCAL).getHour();}
 }

 // ---- input ---------------------------------------------------------------------------------------------------------

 /** Money going out counts as spending, except moves between the customer's own accounts. */
 static List<Spend> spending(List<Map<String,Object>> kampus,List<Map<String,Object>> bank){
  var out=new ArrayList<Spend>();
  for(var t:kampus)add(out,t,"KAMPUS");
  for(var t:bank)add(out,t,"BANK");
  out.sort(Comparator.comparing(Spend::at));
  return out;
 }
 private static void add(List<Spend> out,Map<String,Object> t,String source){
  if(yes(t,"demoHistory")||Set.of("FAILED","REJECTED","CANCELLED","PENDING").contains(str(t,"status"))||!"OUT".equals(str(t,"direction"))||INTERNAL.equals(str(t,"category")))return;
  long amount;Instant at;
  try{amount=num(t,"amountMinor");at=Instant.parse(str(t,"createdAt"));}catch(RuntimeException e){return;}
  if(amount<=0)return;
  String name=str(t,"name"),category=str(t,"category");
  // the wallet has no subscriptions category: older records filed streaming services there
  if(category.equals("Abunəliklər"))category="Əyləncə";
  out.add(new Spend(str(t,"id"),at,amount,category.isBlank()?"Digər":category,name,key(name),str(t,"type"),source));
 }

 /** "BRAVO Supermarket 12" and "Bravo supermarket" are one merchant. */
 static String key(String name){
  String n=name==null?"":name.toUpperCase(Locale.ROOT);
  n=n.replaceAll("[0-9#№*._\\-/\\\\()]+"," ").replaceAll("\\b(MMC|LTD|QSC|ASC|FİLİAL|FILIAL)\\b"," ").replaceAll("\\s+"," ").trim();
  return n.isEmpty()?String.valueOf(name).toUpperCase(Locale.ROOT):n;
 }

 // ---- statistics ----------------------------------------------------------------------------------------------------

 static double median(List<? extends Number> values){
  if(values.isEmpty())return 0;
  var sorted=values.stream().mapToDouble(Number::doubleValue).sorted().toArray();
  int n=sorted.length;
  return n%2==1?sorted[n/2]:(sorted[n/2-1]+sorted[n/2])/2.0;
 }
 static double mad(List<? extends Number> values){
  double med=median(values);
  return median(values.stream().map(v->Math.abs(v.doubleValue()-med)).toList());
 }
 static double mean(double[] v){return v.length==0?0:Arrays.stream(v).average().orElse(0);}
 static double stdev(double[] v){
  if(v.length<2)return 0;
  double m=mean(v);
  return Math.sqrt(Arrays.stream(v).map(x->(x-m)*(x-m)).sum()/(v.length-1));
 }
 /** Standard normal CDF (Abramowitz-Stegun 7.1.26). */
 static double phi(double x){
  double t=1/(1+0.2316419*Math.abs(x)),d=0.3989423*Math.exp(-x*x/2);
  double p=d*t*(0.3193815+t*(-0.3565638+t*(1.781478+t*(-1.821256+t*1.330274))));
  return x>0?1-p:p;
 }

 // ---- recurring payments --------------------------------------------------------------------------------------------

 record Recurring(String key,String label,String category,long typical,String period,int periodDays,int occurrences,LocalDate last,LocalDate next,double confidence){
  long monthly(){return Math.round(typical*30.0/periodDays);}
  /** The occurrence after {@code d}: the same day next month for monthly payments. */
  LocalDate after(LocalDate d){return "MONTHLY".equals(period)?d.plusMonths(1):d.plusDays(periodDays);}
  Map<String,Object> view(){return m("key",key,"label",label,"category",category,"typicalMinor",typical,"period",period,"periodDays",periodDays,"occurrences",occurrences,
   "lastOn",last.toString(),"nextOn",next.toString(),"monthlyMinor",monthly(),"confidence",Math.round(confidence*100)/100.0);}
 }

 static List<Recurring> recurring(List<Spend> spends,Instant now){
  LocalDate today=now.atZone(LOCAL).toLocalDate();
  var byKey=new LinkedHashMap<String,List<Spend>>();
  for(var s:spends)if(s.at().isAfter(now.minus(Duration.ofDays(200))))byKey.computeIfAbsent(s.key(),k->new ArrayList<>()).add(s);
  var out=new ArrayList<Recurring>();
  for(var e:byKey.entrySet()){
   // one entry per day
   var perDay=new TreeMap<LocalDate,Long>();
   for(var s:e.getValue())perDay.merge(s.day(),s.amount(),Long::sum);
   if(perDay.size()<3)continue;
   var days=new ArrayList<>(perDay.keySet());
   var gaps=new ArrayList<Long>();
   for(int i=1;i<days.size();i++)gaps.add(java.time.temporal.ChronoUnit.DAYS.between(days.get(i-1),days.get(i)));
   double gap=median(gaps);
   String period;int periodDays;
   if(gap>=27&&gap<=34){period="MONTHLY";periodDays=30;}
   else if(gap>=13&&gap<=15){period="BIWEEKLY";periodDays=14;}
   else if(gap>=6&&gap<=8){period="WEEKLY";periodDays=7;}
   else continue;
   double gapTol=Math.max(2,0.12*gap);
   double gapIrr=mad(gaps)/gapTol;
   var amounts=new ArrayList<>(perDay.values());
   double typical=median(amounts);
   String category=e.getValue().get(0).category();
   boolean bill="Kommunal".equals(category);
   double amountTol=bill?0.35:0.12;
   double amountIrr=typical<=0?9:(mad(amounts)/typical)/amountTol;
   if(gapIrr>1||amountIrr>1||typical<100)continue;
   double confidence=0.5*(1-Math.min(1,gapIrr))+0.5*(1-Math.min(1,amountIrr));
   if(confidence<0.6)continue;
   LocalDate last=days.get(days.size()-1),next="MONTHLY".equals(period)?last.plusMonths(1):last.plusDays(periodDays);
   if(java.time.temporal.ChronoUnit.DAYS.between(last,today)>1.8*periodDays)continue; // stopped
   var names=e.getValue().stream().collect(Collectors.groupingBy(Spend::merchant,Collectors.counting()));
   String label=names.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
   out.add(new Recurring(e.getKey(),label,category,Math.round(typical),period,periodDays,days.size(),last,next,confidence));
  }
  out.sort(Comparator.comparingLong(Recurring::monthly).reversed());
  return out;
 }

 // ---- unusual payments ----------------------------------------------------------------------------------------------

 record Anomaly(String txId,String reason,double z,int score,String severity,String merchant,String key,String category,long amount,Instant at,String explanation){
  Map<String,Object> view(){return m("txId",txId,"reason",reason,"score",score,"severity",severity,"merchant",merchant,"merchantKey",key,"category",category,"amountMinor",amount,"at",at.toString(),"explanation",explanation);}
 }

 static String azn(long minor){
  double v=minor/100.0;
  return (v>=100?String.format(Locale.ROOT,"%.0f",v):String.format(Locale.ROOT,"%.1f",v)).replace(".0","")+" ₼";
 }

 /** @param ignoredTx payments the customer said were theirs; @param ignoredKeys merchants the customer confirmed */
 static List<Anomaly> anomalies(List<Spend> spends,Instant now,Set<String> ignoredTx,Set<String> ignoredKeys){
  var out=new ArrayList<Anomaly>();
  var burstFlagged=new HashSet<String>();
  var flagged=new HashSet<String>(); // earlier unusual payments must not make the next one look normal
  var recent=spends.stream().filter(s->s.at().isAfter(now.minus(Duration.ofDays(21)))&&!s.at().isAfter(now)).toList();
  for(var t:recent){
   if(ignoredTx.contains(t.id())||ignoredKeys.contains(t.key()))continue;
   Instant from=t.at().minus(Duration.ofDays(120));
   var history=spends.stream().filter(h->h.at().isBefore(t.at())&&h.at().isAfter(from)&&!h.id().equals(t.id())&&!flagged.contains(h.id())).toList();
   var sameCategory=history.stream().filter(h->h.category().equals(t.category())).map(Spend::amount).toList();
   var sameMerchant=history.stream().filter(h->h.key().equals(t.key())).map(Spend::amount).toList();
   // 1. an amount far outside what this merchant (or, with little history, this category or everything) usually costs
   List<Long> base;double zMin,ratioMin;String basis;
   if(sameMerchant.size()>=6){base=sameMerchant;zMin=4;ratioMin=2.5;basis=t.merchant()+" üçün";}
   else if(sameCategory.size()>=5){base=sameCategory;zMin=4;ratioMin=2.5;basis=t.category()+" üçün";}
   else if(history.size()>=20){base=history.stream().map(Spend::amount).toList();zMin=6;ratioMin=6;basis="bir ödəniş üçün";}
   else{base=List.of();zMin=0;ratioMin=0;basis="";}
   if(!base.isEmpty()){
    double med=median(base),scale=Math.max(Math.max(1.4826*mad(base),0.35*med),300);
    double z=(t.amount()-med)/scale;
    long ceiling=base.stream().mapToLong(Long::longValue).max().orElse(0); // a new record, not just the tail of what is normal
    if(z>=zMin&&t.amount()>=ratioMin*med&&t.amount()>=2000&&t.amount()>=1.25*ceiling){
     int score=(int)Math.min(100,Math.round(50+(z-zMin)*8));
     String ratio=String.format(Locale.ROOT,"%.1f",t.amount()/Math.max(med,1)).replace(".0","");
     flagged.add(t.id());
     out.add(new Anomaly(t.id(),"AMOUNT",z,score,score>=80?"HIGH":"MEDIUM",t.merchant(),t.key(),t.category(),t.amount(),t.at(),
      t.merchant()+": "+azn(t.amount())+". "+basis.substring(0,1).toUpperCase(Locale.ROOT)+basis.substring(1)+" adətən təxminən "+azn(Math.round(med))+" xərcləyirsən, bu "+ratio+" dəfə çoxdur."));
     continue;
    }
   }
   // 2. a merchant never seen before, with a large amount
   boolean known=history.stream().anyMatch(h->h.key().equals(t.key()));
   if(!known&&history.size()>=15){
    var cat=sameCategory.stream().sorted().toList();
    double p90=cat.isEmpty()?0:cat.get((int)Math.min(cat.size()-1,Math.floor(cat.size()*0.9)));
    if(t.amount()>=Math.max(p90,4000)*1.5){
     flagged.add(t.id());
     out.add(new Anomaly(t.id(),"NEW_MERCHANT",0,65,"MEDIUM",t.merchant(),t.key(),t.category(),t.amount(),t.at(),
      "İlk dəfə "+t.merchant()+" ("+azn(t.amount())+"). Bu kateqoriyada adi ödənişdən xeyli böyükdür."));
     continue;
    }
   }
   // 3. many payments within a few minutes
   var burst=spends.stream().filter(h->!h.at().isAfter(t.at())&&h.at().isAfter(t.at().minus(Duration.ofMinutes(15)))).toList();
   if(burst.size()>=5&&burst.stream().mapToLong(Spend::amount).sum()>=3000&&burst.stream().noneMatch(h->burstFlagged.contains(h.id()))){
    burstFlagged.add(t.id());
    long total=burst.stream().mapToLong(Spend::amount).sum();
    out.add(new Anomaly(t.id(),"BURST",0,75,"MEDIUM",t.merchant(),t.key(),t.category(),t.amount(),t.at(),
     "15 dəqiqə ərzində "+burst.size()+" ödəniş (cəmi "+azn(total)+"). Bunlar sənsən?"));
   }
  }
  out.sort(Comparator.comparing(Anomaly::at).reversed());
  return out;
 }

 // ---- forecast ------------------------------------------------------------------------------------------------------

 /** @return month so far, projected month end with a band, comparison with last month, and the pace everything else is running at */
 static Map<String,Object> forecast(List<Spend> spends,List<Recurring> recurring,Instant now,Long balance){
  LocalDate today=now.atZone(LOCAL).toLocalDate(),monthStart=today.withDayOfMonth(1),monthEnd=today.withDayOfMonth(today.lengthOfMonth());
  int remaining=today.lengthOfMonth()-today.getDayOfMonth();
  long mtd=spends.stream().filter(s->!s.day().isBefore(monthStart)&&!s.day().isAfter(today)).mapToLong(Spend::amount).sum();
  var recurringKeys=recurring.stream().map(Recurring::key).collect(Collectors.toSet());
  long due=0;
  for(var r:recurring){
   LocalDate next=r.next();
   while(!next.isAfter(monthEnd)){if(next.isAfter(today))due+=r.typical();next=r.after(next);}
  }
  double[] daily=new double[28];
  for(var s:spends){
   long ago=java.time.temporal.ChronoUnit.DAYS.between(s.day(),today);
   if(ago>=0&&ago<28&&!recurringKeys.contains(s.key()))daily[(int)ago]+=s.amount();
  }
  // a single expensive day should not set the whole month's tone: days are capped at the 95th percentile of the last 28
  double[] sorted=Arrays.stream(daily).sorted().toArray();
  double cap=sorted[(int)Math.min(27,Math.floor(27*0.95))];
  double[] capped=Arrays.stream(daily).map(v->Math.min(v,cap)).toArray();
  double rate=mean(capped),sd=stdev(daily);
  // what is left of today still counts: at midnight a whole day is missing from the month so far
  double todayLeft=1-now.atZone(LOCAL).toLocalTime().toSecondOfDay()/86400.0;
  double projected=mtd+due+rate*(remaining+todayLeft);
  // the spread of the remaining days, plus how unsure the pace itself is (28 days of evidence)
  double horizon=remaining+todayLeft;
  double band=1.28*sd*Math.sqrt(horizon+horizon*horizon/28.0);
  double low=Math.max(mtd+due,projected-band),high=projected+band;
  LocalDate lastStart=monthStart.minusMonths(1);
  long lastTotal=spends.stream().filter(s->!s.day().isBefore(lastStart)&&s.day().isBefore(monthStart)).mapToLong(Spend::amount).sum();
  long lastSamePeriod=spends.stream().filter(s->!s.day().isBefore(lastStart)&&!s.day().isAfter(lastStart.plusDays(today.getDayOfMonth()-1L))&&s.day().isBefore(monthStart)).mapToLong(Spend::amount).sum();
  var out=m("monthSpentMinor",mtd,"projectedMinor",Math.round(projected),"lowMinor",Math.round(low),"highMinor",Math.round(high),
   "recurringDueMinor",due,"dailyPaceMinor",Math.round(rate),"daysLeft",remaining,"lastMonthMinor",lastTotal,"lastMonthSamePeriodMinor",lastSamePeriod);
  out.put("vsLastMonthPct",lastTotal>0?Math.round((projected-lastTotal)*100.0/lastTotal):null);
  out.put("vsLastMonthSamePeriodPct",lastSamePeriod>0?Math.round((mtd-lastSamePeriod)*100.0/lastSamePeriod):null);
  double burn=rate+recurring.stream().mapToLong(Recurring::monthly).sum()/30.0;
  out.put("runwayDays",balance!=null&&burn>0?(Object)Math.max(0,(long)Math.floor(balance/burn)):null);
  return out;
 }

 // ---- categories ----------------------------------------------------------------------------------------------------

 static Map<String,Object> breakdown(List<Spend> spends,Instant now){
  LocalDate today=now.atZone(LOCAL).toLocalDate(),monthStart=today.withDayOfMonth(1),prevStart=monthStart.minusMonths(1);
  int elapsed=today.getDayOfMonth();
  var cur=new LinkedHashMap<String,Long>();var prev=new HashMap<String,Long>();
  var topMerchants=new HashMap<String,long[]>();var names=new HashMap<String,String>();
  for(var s:spends){
   if(!s.day().isBefore(monthStart)&&!s.day().isAfter(today)){
    cur.merge(s.category(),s.amount(),Long::sum);
    topMerchants.computeIfAbsent(s.key(),k->new long[2])[0]+=s.amount();topMerchants.get(s.key())[1]++;names.putIfAbsent(s.key(),s.merchant());
   }
   if(!s.day().isBefore(prevStart)&&s.day().isBefore(monthStart)&&s.day().getDayOfMonth()<=elapsed)prev.merge(s.category(),s.amount(),Long::sum);
  }
  long total=cur.values().stream().mapToLong(Long::longValue).sum();
  var categories=new ArrayList<Map<String,Object>>();
  for(var e:cur.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).toList()){
   long before=prev.getOrDefault(e.getKey(),0L);
   var row=m("category",e.getKey(),"amountMinor",e.getValue(),"sharePct",total>0?Math.round(e.getValue()*100.0/total):0,"previousSamePeriodMinor",before);
   row.put("deltaPct",before>=1500?(Object)Math.round((e.getValue()-before)*100.0/before):null);
   categories.add(row);
  }
  var merchants=topMerchants.entrySet().stream().sorted((a,b)->Long.compare(b.getValue()[0],a.getValue()[0])).limit(5)
   .map(e->m("merchant",names.get(e.getKey()),"amountMinor",e.getValue()[0],"count",e.getValue()[1])).toList();
  // six months of totals, oldest first
  var months=new ArrayList<Map<String,Object>>();
  for(int i=5;i>=0;i--){
   LocalDate start=monthStart.minusMonths(i),end=start.plusMonths(1);
   long sum=spends.stream().filter(s->!s.day().isBefore(start)&&s.day().isBefore(end)).mapToLong(Spend::amount).sum();
   months.add(m("month",start.toString().substring(0,7),"amountMinor",sum));
  }
  // average spend on each weekday over the last 90 days
  long[] byWeekday=new long[7];int[] weekdayCount=new int[7];
  for(int i=0;i<90;i++)weekdayCount[today.minusDays(i).getDayOfWeek().getValue()-1]++;
  for(var s:spends)if(!s.day().isAfter(today)&&java.time.temporal.ChronoUnit.DAYS.between(s.day(),today)<90)byWeekday[s.day().getDayOfWeek().getValue()-1]+=s.amount();
  var weekdays=new ArrayList<Long>();
  for(int d=0;d<7;d++)weekdays.add(weekdayCount[d]==0?0L:Math.round(byWeekday[d]/(double)weekdayCount[d]));
  return m("totalMinor",total,"categories",categories,"topMerchants",merchants,"months",months,"weekdayAverageMinor",weekdays);
 }

 // ---- budgets -------------------------------------------------------------------------------------------------------

 /** Median of the last full months of a category plus ten percent, rounded up to 5 AZN. */
 static List<Map<String,Object>> budgetSuggestions(List<Spend> spends,Instant now,Map<String,Long> current){
  LocalDate monthStart=now.atZone(LOCAL).toLocalDate().withDayOfMonth(1);
  var out=new ArrayList<Map<String,Object>>();
  for(String category:spends.stream().map(Spend::category).distinct().sorted().toList()){
   var totals=new ArrayList<Long>();
   for(int i=1;i<=3;i++){
    LocalDate start=monthStart.minusMonths(i),end=start.plusMonths(1);
    long sum=spends.stream().filter(s->s.category().equals(category)&&!s.day().isBefore(start)&&s.day().isBefore(end)).mapToLong(Spend::amount).sum();
    if(sum>0)totals.add(sum);
   }
   if(totals.size()<2)continue;
   double med=median(totals);
   long suggested=(long)(Math.ceil(med*1.1/500.0)*500);
   if(suggested<1000)continue;
   Long limit=current.get(category);
   out.add(m("category",category,"suggestedMinor",suggested,"currentMinor",limit==null?0:limit,"months",totals.size(),
    "basis","Son "+totals.size()+" tam ayın medianı "+azn(Math.round(med))+" + 10%"));
  }
  out.sort(Comparator.comparingLong((Map<String,Object> r)->num(r,"suggestedMinor")).reversed());
  return out;
 }

 /** How likely a category is to pass its monthly limit at the current pace (normal approximation of the remaining days). */
 static List<Map<String,Object>> budgetRisks(List<Spend> spends,List<Recurring> recurring,Map<String,Long> budgets,Instant now){
  LocalDate today=now.atZone(LOCAL).toLocalDate(),monthStart=today.withDayOfMonth(1),monthEnd=today.withDayOfMonth(today.lengthOfMonth());
  int remaining=today.lengthOfMonth()-today.getDayOfMonth();
  var recurringKeys=recurring.stream().map(Recurring::key).collect(Collectors.toSet());
  var out=new ArrayList<Map<String,Object>>();
  for(var b:budgets.entrySet()){
   String category=b.getKey();long limit=b.getValue();
   if(limit<=0)continue;
   long spent=spends.stream().filter(s->s.category().equals(category)&&!s.day().isBefore(monthStart)&&!s.day().isAfter(today)).mapToLong(Spend::amount).sum();
   long due=0;
   for(var r:recurring)if(r.category().equals(category)){LocalDate next=r.next();while(!next.isAfter(monthEnd)){if(next.isAfter(today))due+=r.typical();next=r.after(next);}}
   double[] daily=new double[28];
   for(var s:spends){
    long ago=java.time.temporal.ChronoUnit.DAYS.between(s.day(),today);
    if(ago>=0&&ago<28&&s.category().equals(category)&&!recurringKeys.contains(s.key()))daily[(int)ago]+=s.amount();
   }
   double rate=mean(daily),sd=stdev(daily);
   double projected=spent+due+rate*remaining;
   double probability=remaining==0?(spent>limit?1:0):1-phi((limit-projected)/(sd*Math.sqrt(remaining)+1));
   String risk=spent>=limit||probability>=0.7?"HIGH":probability>=0.35?"MEDIUM":"LOW";
   out.add(m("category",category,"limitMinor",limit,"spentMinor",spent,"projectedMinor",Math.round(projected),"usedPct",Math.round(spent*100.0/limit),
    "exceedProbability",Math.round(probability*100)/100.0,"risk",risk));
  }
  out.sort(Comparator.comparingDouble((Map<String,Object> r)->((Number)r.get("exceedProbability")).doubleValue()).reversed());
  return out;
 }

 // ---- friendly cards ------------------------------------------------------------------------------------------------

 /** The short, friendly messages shown on top of the analytics screen and in the Home card. */
 static List<Map<String,Object>> cards(Map<String,Object> forecast,Map<String,Object> breakdown,List<Recurring> recurring,List<Anomaly> openAnomalies,
   List<Map<String,Object>> risks,List<Map<String,Object>> suggestions){
  var cards=new ArrayList<Map<String,Object>>();
  long projected=num(forecast,"projectedMinor"),last=num(forecast,"lastMonthMinor"),mtd=num(forecast,"monthSpentMinor");
  Object pct=forecast.get("vsLastMonthPct");
  if(mtd>0){
   boolean over=pct!=null&&((Number)pct).longValue()>=15;
   boolean under=pct!=null&&((Number)pct).longValue()<=-10;
   String body="Bu temple ay sonuna təxminən "+azn(projected)+" xərcləyəcəksən (aralıq "+azn(num(forecast,"lowMinor"))+" – "+azn(num(forecast,"highMinor"))+")."
    +(last>0?" Keçən ay "+azn(last)+" idi"+(pct==null?".":over?", təxminən "+pct+"% çoxdur. Qalan günlərdə bir az yavaşlasan, keçən ayı keçməzsən 😉":under?", təxminən "+Math.abs(((Number)pct).longValue())+"% azdır. Əla gedirsən 👏":", demək olar ki, eynidir 🙂"):"");
   cards.add(m("id","forecast","kind","FORECAST","tone",over?"warn":under?"good":"info","icon","trending-up-outline","title","Ay sonuna ~"+azn(projected),"body",body));
  }
  var up=((List<?>)breakdown.get("categories")).stream().map(o->(Map<?,?>)o).filter(r->r.get("deltaPct")!=null&&((Number)r.get("deltaPct")).longValue()>=25&&((Number)r.get("amountMinor")).longValue()-((Number)r.get("previousSamePeriodMinor")).longValue()>=1500)
   .max(Comparator.comparingLong(r->((Number)r.get("amountMinor")).longValue()-((Number)r.get("previousSamePeriodMinor")).longValue()));
  up.ifPresent(r->cards.add(m("id","up-"+r.get("category"),"kind","CATEGORY_UP","tone","warn","icon","arrow-up-circle-outline","title",r.get("category")+" xərcin artıb",
   "body",r.get("category")+" bu ay keçən ayın eyni dövründən "+r.get("deltaPct")+"% çoxdur ("+azn(((Number)r.get("previousSamePeriodMinor")).longValue())+" → "+azn(((Number)r.get("amountMinor")).longValue())+"). Bir az fikir ver 🙂")));
  var down=((List<?>)breakdown.get("categories")).stream().map(o->(Map<?,?>)o).filter(r->r.get("deltaPct")!=null&&((Number)r.get("deltaPct")).longValue()<=-20&&((Number)r.get("previousSamePeriodMinor")).longValue()-((Number)r.get("amountMinor")).longValue()>=1500)
   .max(Comparator.comparingLong(r->((Number)r.get("previousSamePeriodMinor")).longValue()-((Number)r.get("amountMinor")).longValue()));
  down.ifPresent(r->cards.add(m("id","down-"+r.get("category"),"kind","CATEGORY_DOWN","tone","good","icon","happy-outline","title",r.get("category")+" üzrə qənaət",
   "body",r.get("category")+" xərcin keçən ayın eyni dövründən "+Math.abs(((Number)r.get("deltaPct")).longValue())+"% azdır ("+azn(((Number)r.get("previousSamePeriodMinor")).longValue())+" → "+azn(((Number)r.get("amountMinor")).longValue())+"). Afərin 👏")));
  if(!recurring.isEmpty()){
   long monthly=recurring.stream().mapToLong(Recurring::monthly).sum();
   var next=recurring.stream().min(Comparator.comparing(Recurring::next)).get();
   String names=recurring.stream().limit(3).map(Recurring::label).collect(Collectors.joining(", "));
   cards.add(m("id","recurring","kind","RECURRING","tone","info","icon","repeat-outline","title",recurring.size()+" təkrarlanan ödəniş",
    "body","Ayda təxminən "+azn(monthly)+" təkrarlanan ödənişin var ("+names+(recurring.size()>3?" və s.":"")+"). Növbəti: "+next.label()+" – "+next.next().getDayOfMonth()+"."+String.format("%02d",next.next().getMonthValue())+"."));
  }
  if(!openAnomalies.isEmpty())
   cards.add(m("id","anomaly","kind","ANOMALY","tone","warn","icon","alert-circle-outline","title",openAnomalies.size()+" qeyri-adi ödəniş",
    "body","Adi xərcindən fərqlənən "+(openAnomalies.size()==1?"bir ödəniş":openAnomalies.size()+" ödəniş")+" tapdım. Bunlar sənsən? Aşağıda cavab ver."));
  for(var r:risks){
   if("HIGH".equals(r.get("risk"))){
    long spent=num(r,"spentMinor"),limit=num(r,"limitMinor");
    cards.add(m("id","budget-"+r.get("category"),"kind","BUDGET_RISK","tone","warn","icon","speedometer-outline","title",r.get("category")+" büdcəsi risk altındadır",
     "body",r.get("category")+": "+azn(spent)+" / "+azn(limit)+" xərclənib, bu temple ay sonu ~"+azn(num(r,"projectedMinor"))+" olacaq."));
    break;
   }
  }
  Object runway=forecast.get("runwayDays");
  if(runway!=null&&((Number)runway).longValue()<10)
   cards.add(m("id","runway","kind","RUNWAY","tone","warn","icon","hourglass-outline","title","Balans tez bitə bilər","body","Bu tempdə Kampus balansın təxminən "+runway+" günə çatır. Balansı artırmağı unutma 🙂"));
  if(!suggestions.isEmpty())
   cards.add(m("id","suggest","kind","BUDGET_SUGGEST","tone","info","icon","bulb-outline","title","Sənə uyğun büdcə təklifim var","body",suggestions.size()+" kateqoriya üçün son aylarına əsasən limit təklif edirəm. Bir toxunuşla tətbiq edə bilərsən."));
  if(cards.stream().noneMatch(c->"warn".equals(c.get("tone")))&&mtd>0)
   cards.add(m("id","good","kind","GOOD","tone","good","icon","sparkles-outline","title","Yaxşı gedirsən","body","Bu ay nəzərəçarpacaq problem görmürəm. Eyni tempi saxla 🙂"));
  return cards;
 }

 // ---- anonymous overview for the admin ------------------------------------------------------------------------------

 /** Counts only: a group seen in fewer than {@code minUsers} people is folded into "Digər" so nobody can be picked out. */
 static Map<String,Object> aggregate(Map<String,List<Spend>> perUser,Instant now,int minUsers,int anomalyCount,int recurringUsers){
  var all=perUser.values().stream().flatMap(List::stream).filter(s->s.at().isAfter(now.minus(Duration.ofDays(90)))).toList();
  var catAmount=new HashMap<String,Long>();var catUsers=new HashMap<String,Set<String>>();
  for(var e:perUser.entrySet())for(var s:e.getValue())if(s.at().isAfter(now.minus(Duration.ofDays(90)))){
   catAmount.merge(s.category(),s.amount(),Long::sum);catUsers.computeIfAbsent(s.category(),k->new HashSet<>()).add(e.getKey());
  }
  long other=0;var categories=new ArrayList<Map<String,Object>>();
  for(var e:catAmount.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).toList()){
   if(catUsers.get(e.getKey()).size()<minUsers)other+=e.getValue();else categories.add(m("category",e.getKey(),"amountMinor",e.getValue(),"users",catUsers.get(e.getKey()).size()));
  }
  if(other>0)categories.add(m("category","Digər","amountMinor",other,"users",0));
  var methods=new TreeMap<String,Long>();
  for(var s:all)methods.merge(s.source().equals("BANK")?"BANK_CARD":s.type().isBlank()?"OTHER":s.type(),1L,Long::sum);
  long[] hours=new long[24],weekdays=new long[7];
  for(var s:all){hours[s.hour()]++;weekdays[s.at().atZone(LOCAL).getDayOfWeek().getValue()-1]++;}
  var amounts=all.stream().map(Spend::amount).toList();
  long active7=perUser.values().stream().filter(l->l.stream().anyMatch(s->s.at().isAfter(now.minus(Duration.ofDays(7))))).count();
  long active30=perUser.values().stream().filter(l->l.stream().anyMatch(s->s.at().isAfter(now.minus(Duration.ofDays(30))))).count();
  boolean enough=perUser.size()>=minUsers;
  var out=m("users",perUser.size(),"enoughUsers",enough,"minGroup",minUsers,"payments90d",all.size(),"active7d",active7,"active30d",active30,
   "recurringUsers",recurringUsers,"anomalies",anomalyCount,"anomalyRatePer1000",all.isEmpty()?0:Math.round(anomalyCount*10000.0/all.size())/10.0);
  if(enough){
   out.put("totalMinor",all.stream().mapToLong(Spend::amount).sum());
   out.put("medianTicketMinor",Math.round(median(amounts)));out.put("meanTicketMinor",Math.round(mean(amounts.stream().mapToDouble(Long::doubleValue).toArray())));
   out.put("categories",categories);out.put("methods",methods);
   out.put("hours",Arrays.stream(hours).boxed().toList());out.put("weekdays",Arrays.stream(weekdays).boxed().toList());
  }
  return out;
 }
}
