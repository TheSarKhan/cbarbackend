package az.kampus;

import java.time.*;
import java.util.*;
import static az.kampus.Store.*;

/**
 * Believable student spending with known patterns: stipend and family money in, subscriptions and bills out, everyday food and
 * transport, a few entertainment and shopping payments, and a handful of unusual payments in the last weeks. The same generator
 * fills the demo accounts and measures the analytics (the patterns it plants are the answer key).
 */
final class SyntheticSpending {
 private SyntheticSpending(){}

 /** @param txs rows shaped like TX records; @param anomalyIds the planted unusual payments; @param recurringKeys the planted subscriptions and bills */
 record Result(List<Map<String,Object>> txs,Set<String> anomalyIds,Set<String> recurringKeys){
  long in(){return txs.stream().filter(t->"IN".equals(t.get("direction"))).mapToLong(t->num(t,"amountMinor")).sum();}
  long out(){return txs.stream().filter(t->"OUT".equals(t.get("direction"))).mapToLong(t->num(t,"amountMinor")).sum();}
 }

 private record Shop(String name,String category,double minAzn,double maxAzn,double weight){}
 static final List<Shop> FOOD=List.of(new Shop("Bravo Supermarket","Yemək",4,38,3),new Shop("Araz Market","Yemək",3,30,2),new Shop("Papa John's","Yemək",9,26,1.2),
  new Shop("KFC Bakı","Yemək",7,18,1.2),new Shop("Kafe Xazar","Yemək",3,12,2));
 static final List<Shop> TRANSPORT=List.of(new Shop("BakıKart","Nəqliyyat",1.2,2.0,4),new Shop("Bolt","Nəqliyyat",3,14,1));
 static final List<Shop> FUN=List.of(new Shop("CinemaPlus","Əyləncə",8,16,1),new Shop("Steam","Əyləncə",10,45,0.4));
 static final List<Shop> SHOP=List.of(new Shop("Zara","Alış-veriş",30,90,1),new Shop("Baku Mall","Alış-veriş",20,70,1));

 private static Map<String,Object> row(String id,Instant at,String type,String name,long minor,String direction,String category){
  var t=new LinkedHashMap<String,Object>();
  t.put("id",id);t.put("type",type);t.put("name",name);t.put("amountMinor",minor);t.put("direction",direction);t.put("category",category);
  t.put("status","COMPLETED");t.put("createdAt",at.toString());t.put("synthetic",true);
  return t;
 }

 /**
  * @param months how many full months back to start; @param plantAnomalies how many unusual payments to hide in the last three weeks;
  * @param netZero balance the stipend so income equals spending (the wallet balance in the ledger then stays as it was)
  */
 static Result generate(long seed,Instant now,int months,int plantAnomalies,boolean netZero){
  var rnd=new Random(seed);
  String prefix="syn"+Long.toHexString(seed)+"-";
  var txs=new ArrayList<Map<String,Object>>();
  var anomalies=new LinkedHashSet<String>();
  var recurringKeys=new LinkedHashSet<String>();
  LocalDate today=now.atZone(Insights.LOCAL).toLocalDate();
  LocalDate start=today.withDayOfMonth(1).minusMonths(months);
  int[] n={0};
  java.util.function.BiFunction<LocalDate,Integer,Instant> at=(d,hour)->d.atTime(hour,rnd.nextInt(60),rnd.nextInt(60)).toInstant(Insights.LOCAL);
  boolean youtube=seed%2==0;
  for(LocalDate month=start;!month.isAfter(today);month=month.plusMonths(1)){
   int len=month.lengthOfMonth();
   // money in
   LocalDate stipend=month.withDayOfMonth(5);
   if(!stipend.isAfter(today))txs.add(row(prefix+(n[0]++),at.apply(stipend,10),"INCOME","Stipendiya",25_000,"IN","Gəlir"));
   LocalDate family=month.withDayOfMonth(12);
   if(!family.isAfter(today))txs.add(row(prefix+(n[0]++),at.apply(family,15),"INCOME","Ailədən köçürmə",8_000+rnd.nextInt(8)*500L,"IN","Gəlir"));
   // subscriptions: a fixed price on a fixed day
   record Fixed(String name,String category,int day,long minor,double jitter){}
   var fixed=new ArrayList<>(List.of(new Fixed("Netflix","Abunəliklər",10,1299,0),new Fixed("Spotify","Abunəliklər",14,599,0),
    new Fixed("Azercell","Kommunal",8,1500,0.08),new Fixed("AzərSu","Kommunal",18,1000,0.12),new Fixed("Azərişıq","Kommunal",20,2200,0.2)));
   if(youtube)fixed.add(new Fixed("YouTube Premium","Abunəliklər",3,799,0));
   for(var f:fixed){
    LocalDate d=month.withDayOfMonth(Math.min(f.day(),len));
    if(f.jitter()>0)d=d.plusDays(rnd.nextInt(3)-1);
    if(d.isAfter(today))continue;
    long minor=Math.round(f.minor()*(1+(rnd.nextDouble()*2-1)*f.jitter()));
    txs.add(row(prefix+(n[0]++),at.apply(d,9+rnd.nextInt(8)),"BILL",f.name(),minor,"OUT",f.category()));
    recurringKeys.add(Insights.key(f.name()));
   }
   // everyday life
   for(int day=1;day<=len;day++){
    LocalDate d=month.withDayOfMonth(day);
    if(d.isAfter(today))break;
    int meals=rnd.nextInt(100)<12?0:rnd.nextInt(100)<65?1:2;
    for(int i=0;i<meals;i++)txs.add(shopRow(prefix+(n[0]++),at.apply(d,11+rnd.nextInt(10)),pick(rnd,FOOD),rnd));
    int rides=rnd.nextInt(100)<35?0:1+rnd.nextInt(2);
    for(int i=0;i<rides;i++)txs.add(shopRow(prefix+(n[0]++),at.apply(d,7+rnd.nextInt(14)),pick(rnd,TRANSPORT),rnd));
    if(rnd.nextInt(100)<7)txs.add(shopRow(prefix+(n[0]++),at.apply(d,18+rnd.nextInt(4)),pick(rnd,FUN),rnd));
    if(rnd.nextInt(100)<5)txs.add(shopRow(prefix+(n[0]++),at.apply(d,13+rnd.nextInt(6)),pick(rnd,SHOP),rnd));
    if(rnd.nextInt(100)<6)txs.add(row(prefix+(n[0]++),at.apply(d,19),"P2P","Murad Əliyev",500+rnd.nextInt(4)*500L,"OUT","Köçürmələr"));
   }
  }
  // unusual payments in the last three weeks (never in the first days of the month so there is history to compare with)
  var planted=new ArrayList<Map<String,Object>>();
  if(plantAnomalies>=1)planted.add(row(prefix+"anom-1",at.apply(today.minusDays(4),14),"QR","Zara",24_000+rnd.nextInt(5)*1_000L,"OUT","Alış-veriş"));
  if(plantAnomalies>=2)planted.add(row(prefix+"anom-2",at.apply(today.minusDays(9),22),"QR","Tech Store Elektronika",31_000,"OUT","Alış-veriş"));
  if(plantAnomalies>=3){
   Instant burstAt=at.apply(today.minusDays(2),16);
   for(int i=0;i<6;i++)planted.add(row(prefix+"anom-3-"+i,burstAt.plusSeconds(i*90L),"QR","Bolt",700+i*50L,"OUT","Nəqliyyat"));
   anomalies.add(prefix+"anom-3-4"); // the alert fires on the fifth payment inside the window
  }
  for(var p:planted)if(!((String)p.get("id")).startsWith(prefix+"anom-3-"))anomalies.add((String)p.get("id"));
  txs.addAll(planted);
  txs.sort(Comparator.comparing(t->(String)t.get("createdAt")));
  var result=new Result(txs,anomalies,recurringKeys);
  if(netZero){
   // put the difference on the oldest stipend so the ledger-side balance does not move
   long diff=result.out()-result.in();
   for(var t:txs)if("Stipendiya".equals(t.get("name"))){t.put("amountMinor",num(t,"amountMinor")+diff);break;}
  }
  return result;
 }

 private static Shop pick(Random rnd,List<Shop> list){
  double total=list.stream().mapToDouble(Shop::weight).sum(),r=rnd.nextDouble()*total;
  for(var s:list){r-=s.weight();if(r<=0)return s;}
  return list.get(list.size()-1);
 }
 /** Amounts are log-normal inside the shop's usual range: mostly small, now and then bigger. */
 private static Map<String,Object> shopRow(String id,Instant at,Shop s,Random rnd){
  double span=Math.log(s.maxAzn()/s.minAzn());
  double value=s.minAzn()*Math.exp(Math.min(1,Math.abs(rnd.nextGaussian())*0.45)*span);
  return row(id,at,"QR",s.name(),Math.round(value*100),"OUT",s.category());
 }
}
