package az.kampus.sim;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/**
 * Mock open-banking platform with four banks. A customer authorises read-only access on a hosted bank page;
 * accounts, balances and booked transactions are generated deterministically from the bank and the Kampus user,
 * so a later sync returns the same history plus whatever has been booked since.
 */
@RestController
public class OpenBankingSim {
    record Merchant(String name,String mcc,int minAzn,int maxAzn){}
    record Recurring(String name,String mcc,long minor){}
    record Account(String id,String bankId,String type,String name,String masked,String brand,long baseMinor){}

    static final List<Map<String,Object>> BANKS=List.of(
        Map.of("id","KAPITAL","name","Kapital Bank","color","#d4202c","initials","KB"),
        Map.of("id","ABB","name","Beynəlxalq Bank (ABB)","color","#1457a8","initials","AB"),
        Map.of("id","PASHA","name","PAŞA Bank","color","#13804a","initials","PB"),
        Map.of("id","RABITA","name","Rabitəbank","color","#7a2f8f","initials","RB"));

    static final List<Merchant> FOOD=List.of(new Merchant("Bravo Supermarket","5411",4,38),new Merchant("Araz Market","5411",3,30),new Merchant("Rahat Market","5411",3,26),new Merchant("Papa John's","5814",9,26),new Merchant("KFC Bakı","5814",7,18),new Merchant("Starbucks","5814",6,14),new Merchant("Ravat Restoran","5812",18,60),new Merchant("Coffee Moffie","5814",4,12));
    static final List<Merchant> TRANSPORT=List.of(new Merchant("BakıKart","4111",2,10),new Merchant("Bolt","4121",3,14),new Merchant("Uber","4121",4,16),new Merchant("SOCAR Yanacaq","5541",20,60));
    static final List<Merchant> FUN=List.of(new Merchant("CinemaPlus","7832",8,16),new Merchant("Park Cinema","7832",8,16),new Merchant("Steam","5816",10,45),new Merchant("Bowling Club","7933",15,35));
    static final List<Merchant> SHOP=List.of(new Merchant("Zara","5651",30,90),new Merchant("Baku Mall","5311",20,70),new Merchant("Trendyol","5311",12,50),new Merchant("Kontakt Home","5732",40,150));
    static final List<Recurring> SUBSCRIPTIONS=List.of(new Recurring("Netflix","4899",1299),new Recurring("Spotify","4899",599),new Recurring("iCloud","4899",249),new Recurring("YouTube Premium","4899",799),new Recurring("ChatGPT Plus","4899",3400));
    static final List<Recurring> BILLS=List.of(new Recurring("Azercell","4814",1500),new Recurring("Bakcell","4814",1200),new Recurring("AzərSu","4900",1000),new Recurring("Azərİşıq","4900",2200));
    static final int HISTORY_DAYS=90;
    static final LocalDate ANCHOR=LocalDate.of(2026,1,1); // balances are anchored here so new bookings move them consistently

    @Value("${kampus.ob-state-file:.local/open-banking.json}") String stateFile;
    final SimApplication app;
    final Map<String,Map<String,Object>> consents=new LinkedHashMap<>();
    OpenBankingSim(SimApplication app){this.app=app;}

    @jakarta.annotation.PostConstruct void load(){
        try{
            var path=java.nio.file.Path.of(stateFile);
            if(java.nio.file.Files.exists(path))app.json.readTree(path.toFile()).path("consents").fields().forEachRemaining(e->{
                @SuppressWarnings("unchecked") Map<String,Object> value=app.json.convertValue(e.getValue(),LinkedHashMap.class);
                consents.put(e.getKey(),value);
            });
        }catch(Exception ignored){}
    }
    void persist(){
        try{
            var path=java.nio.file.Path.of(stateFile).toAbsolutePath();
            java.nio.file.Files.createDirectories(path.getParent());
            var temp=path.resolveSibling(path.getFileName()+".tmp");
            app.json.writeValue(temp.toFile(),Map.of("consents",consents));
            java.nio.file.Files.move(temp,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }catch(Exception ignored){}
    }

    // ---- deterministic data -------------------------------------------------

    static Random random(String seed){
        try{
            byte[] h=MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            long v=0;for(int i=0;i<8;i++)v=(v<<8)|(h[i]&0xFF);
            return new Random(v);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    static String shortHash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0,10);}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    static Map<String,Object> bank(String id){
        return BANKS.stream().filter(b->b.get("id").equals(id)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"BANK_NOT_FOUND"));
    }

    /** Every customer has a debit card, a virtual card and a current account at each bank. */
    List<Account> accounts(String bankId,String userRef){
        var r=random(bankId+"|"+userRef+"|profile");
        String prefix=bankId.toLowerCase(Locale.ROOT)+"-"+shortHash(userRef);
        String last4=String.format("%04d",r.nextInt(10000)),tail=String.format("%04d",r.nextInt(10000));
        String brand=r.nextBoolean()?"VISA":"MASTERCARD";
        long cardBase=20_000+r.nextInt(40_000),currentBase=90_000+r.nextInt(160_000);
        String lastB=String.format("%04d",r.nextInt(10000)),brandB=brand.equals("VISA")?"MASTERCARD":"VISA";
        long cardBaseB=15_000+r.nextInt(30_000);
        return List.of(
            new Account(prefix+"-card",bankId,"CARD","Debet kart · "+brand,"•••• "+last4,brand,cardBase),
            new Account(prefix+"-card2",bankId,"CARD","Virtual kart · "+brandB,"•••• "+lastB,brandB,cardBaseB),
            new Account(prefix+"-current",bankId,"CURRENT","Cari hesab","AZ•• •••• •••• "+tail,"",currentBase));
    }

    static long azn(Random r,int min,int max){return Math.round((min+r.nextDouble()*(max-min))*100);}
    static Merchant pick(Random r,List<Merchant> list){return list.get(r.nextInt(list.size()));}

    List<Map<String,Object>> transactions(Account account,Instant now,LocalDate first){
        List<Map<String,Object>> out=new ArrayList<>();
        LocalDate today=LocalDateTime.ofInstant(now,ZoneOffset.UTC).toLocalDate();
        var profile=random(account.id()+"|recurring");
        boolean card=account.type().equals("CARD");
        int subscriptionDay=2+profile.nextInt(24),billDay=8+profile.nextInt(10);
        Recurring subscriptionA=SUBSCRIPTIONS.get(profile.nextInt(SUBSCRIPTIONS.size())),subscriptionB=SUBSCRIPTIONS.get(profile.nextInt(SUBSCRIPTIONS.size()));
        Recurring billA=BILLS.get(profile.nextInt(BILLS.size())),billB=BILLS.get(profile.nextInt(BILLS.size()));
        for(LocalDate day=first;!day.isAfter(today);day=day.plusDays(1)){
            var r=random(account.id()+"|"+day);
            int index=0;
            if(card){
                int roll=r.nextInt(100),count=roll<35?0:roll<75?1:roll<95?2:3;
                for(int i=0;i<count;i++){
                    int kind=r.nextInt(100);
                    var m=kind<60?pick(r,FOOD):kind<85?pick(r,TRANSPORT):kind<94?pick(r,FUN):pick(r,SHOP);
                    add(out,account,day,index++,r,m.name(),m.mcc(),azn(r,m.minAzn(),m.maxAzn()),"OUT",8+r.nextInt(15));
                }
                if(day.getDayOfMonth()==6)add(out,account,day,index++,r,"Köçürmə: cari hesabdan","0001",60_000,"IN",12);
                if(day.getDayOfMonth()==subscriptionDay){
                    add(out,account,day,index++,r,subscriptionA.name(),subscriptionA.mcc(),subscriptionA.minor(),"OUT",9);
                    if(subscriptionB!=subscriptionA)add(out,account,day,index++,r,subscriptionB.name(),subscriptionB.mcc(),subscriptionB.minor(),"OUT",10);
                }
            }else{
                if(day.getDayOfMonth()==5)add(out,account,day,index++,r,"Stipendiya","0000",azn(r,180,320),"IN",11);
                if(day.getDayOfMonth()==6)add(out,account,day,index++,r,"Kartə köçürmə","0001",60_000,"OUT",12);
                if(day.getDayOfMonth()==8)add(out,account,day,index++,r,"Köçürmə: ailə","6012",azn(r,350,550),"IN",15);
                if(day.getDayOfMonth()==billDay){
                    add(out,account,day,index++,r,billA.name(),billA.mcc(),billA.minor(),"OUT",12);
                    if(billB!=billA)add(out,account,day,index++,r,billB.name(),billB.mcc(),billB.minor(),"OUT",13);
                }
                int roll=r.nextInt(100);
                if(roll<9)add(out,account,day,index++,r,"Köçürmə: dost","6012",azn(r,10,40),"OUT",14+r.nextInt(8));
                else if(roll<12)add(out,account,day,index++,r,"Köçürmə: ailə","6012",azn(r,20,120),"IN",10+r.nextInt(8));
                else if(roll<15)add(out,account,day,index++,r,"ATM nağdlaşdırma","6011",azn(r,20,100),"OUT",9+r.nextInt(12));
            }
        }
        out.removeIf(t->Instant.parse(t.get("bookedAt").toString()).isAfter(now));
        out.sort(Comparator.comparing((Map<String,Object> t)->t.get("bookedAt").toString()).reversed());
        return out;
    }
    static void add(List<Map<String,Object>> out,Account account,LocalDate day,int index,Random r,String name,String mcc,long minor,String direction,int hour){
        Instant booked=day.atTime(hour,r.nextInt(60),r.nextInt(60)).toInstant(ZoneOffset.UTC);
        out.add(new LinkedHashMap<>(Map.of("id",account.id()+"-"+day+"-"+index,"accountId",account.id(),"bookedAt",booked.toString(),"amountMinor",minor,"direction",direction,"name",name,"mcc",mcc,"status","BOOKED")));
    }
    List<Map<String,Object>> history(Account account,Instant now){
        LocalDate today=LocalDateTime.ofInstant(now,ZoneOffset.UTC).toLocalDate();
        return transactions(account,now,today.minusDays(HISTORY_DAYS-1L));
    }
    long balance(Account account,Instant now){
        long net=0;
        for(var t:transactions(account,now,ANCHOR)){long a=((Number)t.get("amountMinor")).longValue();net+="IN".equals(t.get("direction"))?a:-a;}
        return Math.max(1_000,account.baseMinor()+net);
    }

    // ---- consent lifecycle --------------------------------------------------

    Map<String,Object> consent(String id){
        var c=consents.get(id);
        if(c==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"CONSENT_NOT_FOUND");
        expire(c);
        return c;
    }
    void expire(Map<String,Object> c){
        long now=System.currentTimeMillis();
        if("AUTHORIZED".equals(c.get("status"))&&((Number)c.get("expiresAt")).longValue()<now){c.put("status","EXPIRED");persist();}
        if("AWAITING_AUTHORIZATION".equals(c.get("status"))&&((Number)c.get("pageExpiresAt")).longValue()<now){c.put("status","EXPIRED");persist();}
    }
    static String text(Map<String,Object> b,String key){return Objects.toString(b.get(key),"");}

    @PostMapping("/openbanking/banks") Object banks(@RequestHeader("X-Api-Key")String key){app.authorize(key);return Map.of("banks",BANKS);}

    static final List<Integer> MONTHS=List.of(1,2,3,6,12);
    static final long MIN_LIMIT_MINOR=1_000,MAX_LIMIT_MINOR=1_000_000;

    /** The bank knows a person by phone and FİN. The seeded demo people bank everywhere; for anyone else roughly three banks in four know them. */
    static boolean isCustomer(String bankId,String phone,String fin){
        if(phone.matches("\\+99450100000[0-9]"))return true;
        return random(bankId+"|customer|"+fin).nextInt(4)!=0;
    }
    static List<Map<String,Object>> cardView(List<Account> accounts,long now,OpenBankingSim sim,Map<String,Object> c){
        return accounts.stream().map(a->{
            var m=new LinkedHashMap<String,Object>();
            m.put("id",a.id());m.put("type",a.type());m.put("name",a.name());m.put("masked",a.masked());m.put("brand",a.brand());m.put("currency","AZN");
            m.put("balanceMinor",sim.availableBalance(a,c,Instant.ofEpochMilli(now)));
            return (Map<String,Object>)m;
        }).toList();
    }

    /**
     * Kampus asks the bank to open a data and payment consent for a person. The bank looks the person up by phone and FİN;
     * a customer gets a push notification in the bank app, a stranger gets "not a customer".
     */
    @PostMapping("/openbanking/request") synchronized Object request(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        String bankId=text(b,"bankId"),userRef=text(b,"userRef"),phone=text(b,"phone"),fin=text(b,"fin");
        var bank=bank(bankId);
        if(userRef.isBlank()||phone.isBlank()||fin.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"IDENTITY_REQUIRED");
        if(!isCustomer(bankId,phone,fin))return Map.of("customer",false);
        for(var old:consents.values())if(bankId.equals(old.get("bankId"))&&userRef.equals(old.get("userRef"))&&"AWAITING_AUTHORIZATION".equals(old.get("status")))old.put("status","EXPIRED");
        String id=UUID.randomUUID().toString();
        var c=new LinkedHashMap<String,Object>();
        c.put("id",id);c.put("bankId",bankId);c.put("userRef",userRef);c.put("phone",phone);c.put("status","AWAITING_AUTHORIZATION");c.put("step","NOTIFIED");
        c.put("createdAt",System.currentTimeMillis());c.put("pageExpiresAt",System.currentTimeMillis()+3_600_000);c.put("accountIds",new ArrayList<String>());c.put("charges",new ArrayList<Map<String,Object>>());
        consents.put(id,c);persist();
        return Map.of("customer",true,"consentId",id,
            "notification",Map.of("title",bank.get("name"),"body","Kampus tətbiqi hesab və kartlarınıza baxış və ödəniş icazəsi istəyir. Ətraflı baxmaq üçün toxunun."),
            "cards",cardView(accounts(bankId,userRef),System.currentTimeMillis(),this,c),"allowedMonths",MONTHS);
    }

    /** The customer picks cards, how long the permission lasts and the monthly spending limit inside the bank app. */
    @PostMapping("/openbanking/select") synchronized Object select(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var c=consent(text(b,"consentId"));
        if(!"AWAITING_AUTHORIZATION".equals(c.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"CONSENT_NOT_OPEN");
        var valid=accounts(text(c,"bankId"),text(c,"userRef")).stream().map(Account::id).toList();
        @SuppressWarnings("unchecked") var raw=(List<Object>)b.getOrDefault("accountIds",List.of());
        var chosen=raw.stream().map(Object::toString).filter(valid::contains).distinct().toList();
        if(chosen.isEmpty())throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"NO_ACCOUNT_SELECTED");
        int months;long limit;
        try{months=Integer.parseInt(text(b,"months"));limit=Long.parseLong(text(b,"monthlyLimitMinor"));}catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_TERMS");}
        if(!MONTHS.contains(months)||limit<MIN_LIMIT_MINOR||limit>MAX_LIMIT_MINOR)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_TERMS");
        c.put("accountIds",new ArrayList<>(chosen));c.put("months",months);c.put("monthlyLimitMinor",limit);c.put("step","SELECTED");persist();
        return Map.of("step","SELECTED","accountIds",chosen,"months",months,"monthlyLimitMinor",limit);
    }

    /** The signed consent document switches the permission on. */
    @PostMapping("/openbanking/sign") synchronized Object sign(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var c=consent(text(b,"consentId"));
        if(!"AWAITING_AUTHORIZATION".equals(c.get("status"))||!"SELECTED".equals(c.get("step")))throw new ResponseStatusException(HttpStatus.CONFLICT,"CONSENT_NOT_READY");
        if(text(b,"signatureHash").isBlank()||text(b,"contractId").isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"SIGNATURE_REQUIRED");
        long now=System.currentTimeMillis(),months=((Number)c.get("months")).longValue();
        c.put("status","AUTHORIZED");c.put("step","SIGNED");c.put("authorizedAt",now);c.put("expiresAt",text(b,"expiresAt").isBlank()?now+months*30L*86_400_000L:Long.parseLong(text(b,"expiresAt")));
        c.put("contractId",text(b,"contractId"));c.put("signatureHash",text(b,"signatureHash"));persist();
        return Map.of("status","AUTHORIZED","expiresAt",c.get("expiresAt"));
    }

    static long monthSpent(Map<String,Object> c){
        String month=LocalDate.now(ZoneOffset.UTC).toString().substring(0,7);
        @SuppressWarnings("unchecked") var charges=(List<Map<String,Object>>)c.getOrDefault("charges",List.of());
        return charges.stream().filter(x->!Boolean.TRUE.equals(x.get("reversed"))&&text(x,"at").startsWith(month)).mapToLong(x->((Number)x.get("amountMinor")).longValue()).sum();
    }
    static long charged(Map<String,Object> c,String accountId){
        @SuppressWarnings("unchecked") var charges=(List<Map<String,Object>>)c.getOrDefault("charges",List.of());
        return charges.stream().filter(x->!Boolean.TRUE.equals(x.get("reversed"))&&accountId.equals(x.get("accountId"))).mapToLong(x->((Number)x.get("amountMinor")).longValue()).sum();
    }
    long availableBalance(Account a,Map<String,Object> c,Instant now){return Math.max(0,balance(a,now)-charged(c,a.id()));}

    /** Kampus pays out of a shared card. The bank enforces what the customer agreed to: selected card, monthly limit, balance. */
    @PostMapping("/openbanking/charge") synchronized Object charge(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var c=consent(text(b,"consentId"));
        if(!"AUTHORIZED".equals(c.get("status")))return Map.of("declined",true,"code","CONSENT_INACTIVE");
        if(c.get("monthlyLimitMinor")==null)return Map.of("declined",true,"code","PAYMENTS_NOT_ALLOWED"); // linked before payments existed: read-only
        String accountId=text(b,"accountId");
        @SuppressWarnings("unchecked") var allowed=(List<String>)c.get("accountIds");
        if(!allowed.contains(accountId))return Map.of("declined",true,"code","ACCOUNT_NOT_SHARED");
        long amount=Long.parseLong(text(b,"amountMinor"));
        if(amount<=0)return Map.of("declined",true,"code","INVALID_AMOUNT");
        long limit=((Number)c.get("monthlyLimitMinor")).longValue(),spent=monthSpent(c);
        if(spent+amount>limit)return Map.of("declined",true,"code","LIMIT_EXCEEDED","monthlyLimitMinor",limit,"monthlySpentMinor",spent);
        var account=accounts(text(c,"bankId"),text(c,"userRef")).stream().filter(a->a.id().equals(accountId)).findFirst().orElseThrow();
        long available=availableBalance(account,c,Instant.now());
        if(available<amount)return Map.of("declined",true,"code","INSUFFICIENT_FUNDS","availableMinor",available);
        String id="chg-"+UUID.randomUUID().toString().substring(0,12);
        @SuppressWarnings("unchecked") var charges=(List<Map<String,Object>>)c.get("charges");
        charges.add(new LinkedHashMap<>(Map.of("id",id,"accountId",accountId,"amountMinor",amount,"reference",text(b,"reference"),"at",Instant.now().toString())));
        persist();
        return Map.of("declined",false,"chargeId",id,"balanceAfterMinor",available-amount,"monthlySpentMinor",spent+amount,"monthlyLimitMinor",limit);
    }

    @PostMapping("/openbanking/reverse") synchronized Object reverse(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var c=consent(text(b,"consentId"));
        @SuppressWarnings("unchecked") var charges=(List<Map<String,Object>>)c.getOrDefault("charges",List.of());
        for(var x:charges)if(text(b,"chargeId").equals(text(x,"id"))){x.put("reversed",true);persist();return Map.of("reversed",true);}
        return Map.of("reversed",false);
    }

    @PostMapping("/openbanking/consent-status") synchronized Object status(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var c=consent(text(b,"consentId"));
        var out=new LinkedHashMap<String,Object>(Map.of("status",c.get("status"),"bankId",c.get("bankId"),"accountIds",c.get("accountIds")));
        if(c.get("expiresAt")!=null)out.put("expiresAt",c.get("expiresAt"));
        return out;
    }

    /** Accounts and balances: only the ones the customer ticked, only while the consent is live. */
    @PostMapping("/openbanking/accounts") synchronized Object accountsFor(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var c=consent(text(b,"consentId"));
        if(!"AUTHORIZED".equals(c.get("status")))return Map.of("status",c.get("status"),"accounts",List.of());
        Instant now=Instant.now();
        @SuppressWarnings("unchecked") var allowed=(List<String>)c.get("accountIds");
        var list=accounts(text(c,"bankId"),text(c,"userRef")).stream().filter(a->allowed.contains(a.id())).map(a->{
            var m=new LinkedHashMap<String,Object>();
            m.put("id",a.id());m.put("type",a.type());m.put("name",a.name());m.put("masked",a.masked());m.put("brand",a.brand());m.put("currency","AZN");
            long balance=availableBalance(a,c,now);m.put("balanceMinor",balance);m.put("availableMinor",balance);
            return (Map<String,Object>)m;
        }).toList();
        return Map.of("status","AUTHORIZED","accounts",list);
    }

    @PostMapping("/openbanking/transactions") synchronized Object transactionsFor(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var c=consent(text(b,"consentId"));
        if(!"AUTHORIZED".equals(c.get("status")))return Map.of("status",c.get("status"),"transactions",List.of());
        Instant now=Instant.now();
        String since=text(b,"since");
        Instant from=since.isBlank()?Instant.EPOCH:Instant.parse(since);
        @SuppressWarnings("unchecked") var allowed=(List<String>)c.get("accountIds");
        List<Map<String,Object>> out=new ArrayList<>();
        for(var a:accounts(text(c,"bankId"),text(c,"userRef")))if(allowed.contains(a.id()))
            for(var t:history(a,now))if(!Instant.parse(t.get("bookedAt").toString()).isBefore(from))out.add(t);
        @SuppressWarnings("unchecked") var charges=(List<Map<String,Object>>)c.getOrDefault("charges",List.of());
        for(var x:charges)if(!Boolean.TRUE.equals(x.get("reversed"))&&allowed.contains(text(x,"accountId"))&&!Instant.parse(text(x,"at")).isBefore(from))
            out.add(new LinkedHashMap<>(Map.of("id",text(x,"id"),"accountId",text(x,"accountId"),"bookedAt",text(x,"at"),"amountMinor",x.get("amountMinor"),"direction","OUT","name","Kampus: "+text(x,"reference"),"mcc","0001","status","BOOKED")));
        return Map.of("status","AUTHORIZED","transactions",out);
    }

    @PostMapping("/openbanking/revoke") synchronized Object revoke(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var c=consent(text(b,"consentId"));
        c.put("status","REVOKED");c.put("accountIds",List.of());persist();
        return Map.of("status","REVOKED");
    }
}
