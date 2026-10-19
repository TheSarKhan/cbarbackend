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
    @Value("${kampus.ob-consent-days:90}") int consentDays;
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

    /** Every customer has a debit card and a current account at each bank. */
    List<Account> accounts(String bankId,String userRef){
        var r=random(bankId+"|"+userRef+"|profile");
        String prefix=bankId.toLowerCase(Locale.ROOT)+"-"+shortHash(userRef);
        String last4=String.format("%04d",r.nextInt(10000)),tail=String.format("%04d",r.nextInt(10000));
        String brand=r.nextBoolean()?"VISA":"MASTERCARD";
        long cardBase=20_000+r.nextInt(40_000),currentBase=90_000+r.nextInt(160_000);
        return List.of(
            new Account(prefix+"-card",bankId,"CARD","Debet kart · "+brand,"•••• "+last4,brand,cardBase),
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

    @PostMapping("/openbanking/consents") synchronized Object create(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        String bankId=text(b,"bankId"),userRef=text(b,"userRef");
        bank(bankId);
        if(userRef.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"USER_REQUIRED");
        String id=UUID.randomUUID().toString();
        consents.put(id,new LinkedHashMap<>(Map.of("id",id,"bankId",bankId,"userRef",userRef,"status","AWAITING_AUTHORIZATION","nonce",UUID.randomUUID().toString(),"createdAt",System.currentTimeMillis(),"pageExpiresAt",System.currentTimeMillis()+900_000,"accountIds",List.of())));
        persist();
        return Map.of("consentId",id,"authorizationPath","/ob-consent/"+id);
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
            long balance=balance(a,now);m.put("balanceMinor",balance);m.put("availableMinor",balance);
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
        return Map.of("status","AUTHORIZED","transactions",out);
    }

    @PostMapping("/openbanking/revoke") synchronized Object revoke(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var c=consent(text(b,"consentId"));
        c.put("status","REVOKED");c.put("accountIds",List.of());persist();
        return Map.of("status","REVOKED");
    }

    // ---- hosted bank page ---------------------------------------------------

    static String esc(String v){return v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    static ResponseEntity<String> html(HttpStatus status,String bankName,String color,String inner){
        String page="<!doctype html><html lang=\"az\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"+esc(bankName)+" · İcazə</title><style>"
            +"*{box-sizing:border-box}body{margin:0;background:#f4f5f2;color:#17201c;font:15px system-ui,sans-serif}"
            +".bar{background:"+color+";color:#fff;padding:22px 24px}.bar b{font-size:20px;letter-spacing:-.3px}.bar span{display:block;font-size:11px;opacity:.85;margin-top:4px;letter-spacing:1px}"
            +"main{max-width:480px;margin:0 auto;padding:24px}h1{font-size:26px;letter-spacing:-.8px;margin:6px 0 10px}p{line-height:1.6;color:#59635d;margin:8px 0}"
            +".box{background:#fff;border:1px solid #e1e5df;border-radius:16px;padding:6px 16px;margin:16px 0}.row{display:flex;gap:12px;align-items:center;padding:12px 0;border-bottom:1px solid #eef0ec}.row:last-child{border:0}"
            +".row label{flex:1;display:flex;gap:12px;align-items:center;cursor:pointer}.row input{width:20px;height:20px;accent-color:"+color+"}.muted{color:#7a847e;font-size:12px}"
            +"ul{padding-left:18px;margin:8px 0}li{margin:6px 0;line-height:1.5}.actions{display:flex;gap:10px;margin-top:18px}"
            +"button{flex:1;border:0;border-radius:14px;padding:16px;font:600 15px system-ui;cursor:pointer}.ok{background:"+color+";color:#fff}.no{background:#e9ece7;color:#17201c}</style>"
            +"<div class=\"bar\"><b>"+esc(bankName)+"</b><span>MOCK AÇIQ BANKÇILIQ · SANDBOX</span></div><main>"+inner+"</main></html>";
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).header("Cache-Control","no-store").body(page);
    }

    @GetMapping(value="/ob-consent/{id}",produces=MediaType.TEXT_HTML_VALUE)
    synchronized ResponseEntity<String> page(@PathVariable String id){
        var c=consent(id);
        var bank=bank(text(c,"bankId"));
        String name=bank.get("name").toString(),color=bank.get("color").toString();
        String status=text(c,"status");
        if(!"AWAITING_AUTHORIZATION".equals(status)){
            String message=switch(status){
                case "AUTHORIZED"->"<h1>İcazə verildi ✓</h1><p>Kampus tətbiqinə qayıdın. Hesablarınız bir neçə saniyəyə görünəcək.</p>";
                case "REJECTED"->"<h1>İcazə rədd edildi</h1><p>Kampus heç bir məlumat almadı. Tətbiqə qayıda bilərsiniz.</p>";
                case "REVOKED"->"<h1>İcazə ləğv edilib</h1><p>Bu icazə artıq etibarlı deyil.</p>";
                default->"<h1>Vaxt bitdi</h1><p>Bu səhifənin vaxtı bitib. Kampus tətbiqindən yenidən başlayın.</p>";
            };
            return html(HttpStatus.OK,name,color,message);
        }
        var rows=new StringBuilder();
        for(var a:accounts(text(c,"bankId"),text(c,"userRef")))
            rows.append("<div class=\"row\"><label><input type=\"checkbox\" name=\"account\" form=\"consent\" value=\"").append(esc(a.id())).append("\" checked><span><b>")
                .append(esc(a.name())).append("</b><br><span class=\"muted\">").append(esc(a.masked())).append("</span></span></label></div>");
        String inner="<p class=\"muted\">KAMPUS TƏTBİQİ İCAZƏ İSTƏYİR</p><h1>Hesablarına baxış icazəsi</h1>"
            +"<p>Kampus aşağıdakı hesab və kartlarının balansını və əməliyyatlarını göstərmək üçün icazə istəyir. İstədiklərini seçə bilərsən.</p>"
            +"<div class=\"box\">"+rows+"</div>"
            +"<div class=\"box\"><ul><li>Hesab və kart balansı</li><li>Son "+HISTORY_DAYS+" günün əməliyyat tarixçəsi</li><li><b>Yalnız oxuma:</b> Kampus bu hesablardan pul köçürə bilməz</li><li>İcazə "+consentDays+" gün etibarlıdır və istənilən vaxt ləğv edilə bilər</li></ul></div>"
            +"<form id=\"consent\" method=\"post\" action=\"/ob-consent/"+esc(id)+"\"><input type=\"hidden\" name=\"nonce\" value=\""+esc(text(c,"nonce"))+"\">"
            +"<div class=\"actions\"><button class=\"no\" name=\"decision\" value=\"reject\">Rədd et</button><button class=\"ok\" name=\"decision\" value=\"approve\">İcazə ver</button></div></form>"
            +"<p class=\"muted\">Sandbox: real bank girişi və məlumatları yoxdur.</p>";
        return html(HttpStatus.OK,name,color,inner);
    }

    @PostMapping(value="/ob-consent/{id}",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE,produces=MediaType.TEXT_HTML_VALUE)
    synchronized ResponseEntity<String> decide(@PathVariable String id,@RequestParam MultiValueMap<String,String> form){
        var c=consent(id);
        if(!Objects.equals(form.getFirst("nonce"),c.get("nonce")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var bank=bank(text(c,"bankId"));
        String name=bank.get("name").toString(),color=bank.get("color").toString();
        if(!"AWAITING_AUTHORIZATION".equals(c.get("status")))return page(id);
        if("reject".equals(form.getFirst("decision"))){
            c.put("status","REJECTED");persist();
            return html(HttpStatus.OK,name,color,"<h1>İcazə rədd edildi</h1><p>Kampus heç bir məlumat almadı. Tətbiqə qayıda bilərsiniz.</p>");
        }
        var valid=accounts(text(c,"bankId"),text(c,"userRef")).stream().map(Account::id).toList();
        var chosen=(form.get("account")==null?List.<String>of():form.get("account")).stream().filter(valid::contains).distinct().toList();
        if(chosen.isEmpty())return html(HttpStatus.UNPROCESSABLE_ENTITY,name,color,"<h1>Hesab seçilməyib</h1><p>Ən azı bir hesab və ya kart seçin.</p><p><a href=\"/ob-consent/"+esc(id)+"\">Geri qayıt</a></p>");
        long now=System.currentTimeMillis();
        c.put("status","AUTHORIZED");c.put("accountIds",new ArrayList<>(chosen));c.put("authorizedAt",now);c.put("expiresAt",now+consentDays*86_400_000L);
        persist();
        return html(HttpStatus.OK,name,color,"<h1>İcazə verildi ✓</h1><p>Kampus tətbiqinə qayıdın. Hesablarınız bir neçə saniyəyə görünəcək.</p><p class=\"muted\">İcazə "+consentDays+" gün etibarlıdır.</p>");
    }
}
