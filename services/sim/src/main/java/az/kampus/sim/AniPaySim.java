package az.kampus.sim;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * AniPay between banks, seen from the receiving side. Kampus hands a transfer to AniPay with the recipient's FİN and phone;
 * it waits there until the recipient opens AniPay in their own bank app (simulated in the wallet like the open-banking bank app):
 * FİN and phone, an SMS code, then the card or current account that should receive it. Unclaimed transfers expire and Kampus takes the money back.
 */
@RestController
public class AniPaySim {
    static final String SHORT="[A-Z0-9]{7}";
    @Value("${kampus.anipay-state-file:.local/anipay.json}") String stateFile;
    @Value("${kampus.anipay-ttl-ms:86400000}") long ttlMs;
    final SimApplication app;final OpenBankingSim banks;
    final SecureRandom random=new SecureRandom();
    final Map<String,Map<String,Object>> transfers=new LinkedHashMap<>(),otps=new LinkedHashMap<>();
    AniPaySim(SimApplication app,OpenBankingSim banks){this.app=app;this.banks=banks;}

    @jakarta.annotation.PostConstruct void load(){
        try{
            var path=java.nio.file.Path.of(stateFile);
            if(!java.nio.file.Files.exists(path))return;
            var root=app.json.readTree(path.toFile());
            for(var kind:List.of("transfers","otps")){
                var target=kind.equals("transfers")?transfers:otps;
                root.path(kind).fields().forEachRemaining(e->{
                    @SuppressWarnings("unchecked") Map<String,Object> value=app.json.convertValue(e.getValue(),LinkedHashMap.class);
                    target.put(e.getKey(),value);
                });
            }
        }catch(Exception ignored){}
    }
    void persist(){
        try{
            var path=java.nio.file.Path.of(stateFile).toAbsolutePath();
            java.nio.file.Files.createDirectories(path.getParent());
            var temp=path.resolveSibling(path.getFileName()+".tmp");
            app.json.writeValue(temp.toFile(),Map.of("transfers",transfers,"otps",otps));
            java.nio.file.Files.move(temp,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }catch(Exception ignored){}
    }
    static String text(Map<String,Object> b,String key){return Objects.toString(b.get(key),"").trim();}
    static long amount(Map<String,Object> t){return ((Number)t.get("amountMinor")).longValue();}
    static String phoneKey(String phone){return phone.replaceAll("[^0-9+]","");}
    boolean open(Map<String,Object> t){
        if("PENDING".equals(t.get("status"))&&((Number)t.get("expiresAt")).longValue()<System.currentTimeMillis()){t.put("status","EXPIRED");persist();}
        return "PENDING".equals(t.get("status"));
    }

    // ---- Kampus side (AniPay API) -------------------------------------------

    @PostMapping("/anipay/transfers") synchronized Object submit(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        // the sender names the recipient by phone or by FİN, one of the two
        String id=text(b,"transferId"),fin=text(b,"fin").toUpperCase(Locale.ROOT),phone=phoneKey(text(b,"phone"));
        if(transfers.containsKey(id))return Map.of("status",transfers.get(id).get("status"),"reference",id);
        long a;try{a=new java.math.BigDecimal(b.get("amountMinor").toString()).longValueExact();}catch(Exception e){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_AMOUNT");}
        if(id.isBlank()||a<=0||(fin.isBlank()==phone.isBlank())||(!fin.isBlank()&&!fin.matches(SHORT)))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_TRANSFER");
        var t=new LinkedHashMap<String,Object>();
        t.put("transferId",id);t.put("fin",fin);t.put("phone",phone);t.put("amountMinor",a);t.put("senderName",text(b,"senderName"));
        t.put("status","PENDING");t.put("createdAt",System.currentTimeMillis());t.put("expiresAt",System.currentTimeMillis()+ttlMs);
        transfers.put(id,t);persist();
        return Map.of("status","PENDING","reference",id);
    }
    @PostMapping("/anipay/status") synchronized Object status(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var t=transfers.get(text(b,"transferId"));
        if(t==null)return Map.of("status","UNKNOWN");
        open(t);
        var out=new LinkedHashMap<String,Object>(Map.of("status",t.get("status")));
        if("COMPLETED".equals(t.get("status")))out.put("receivedAt",t.getOrDefault("receivedAt",""));
        if("COMPLETED".equals(t.get("status")))out.put("receivedTo",t.getOrDefault("receivedTo",""));
        return out;
    }
    /** Demo control, like the card network's: lets a presenter (and the tests) let an unclaimed transfer run out. */
    @PostMapping("/anipay/demo-expire") synchronized Object expire(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var t=transfers.get(text(b,"transferId"));
        if(t==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(!"PENDING".equals(t.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT);
        t.put("status","EXPIRED");persist();return Map.of("status","EXPIRED","demo",true);
    }

    // ---- the recipient's bank app --------------------------------------------

    /** A transfer addressed by FİN is found by the FİN, one addressed by phone by the phone. */
    static boolean matches(Map<String,Object> t,String fin,String phone){
        return text(t,"fin").isBlank()?phone.equals(text(t,"phone")):fin.equals(text(t,"fin"));
    }
    Map<String,Object> bankOf(String id){return OpenBankingSim.bank(id.toUpperCase(Locale.ROOT));}
    Map<String,Object> session(String id){
        var s=otps.get(id);
        if(s==null||((Number)s.get("expiresAt")).longValue()<System.currentTimeMillis())throw new ResponseStatusException(HttpStatus.GONE,"SESSION_EXPIRED");
        return s;
    }
    List<Map<String,Object>> accountViews(String bankId,String fin){
        var now=Instant.now();
        return banks.accounts(bankId,"fin:"+fin).stream().map(a->{
            var m=new LinkedHashMap<String,Object>();
            m.put("id",a.id());m.put("type",a.type());m.put("name",a.name());m.put("masked",a.masked());m.put("brand",a.brand());m.put("balanceMinor",banks.balance(a,now));
            return (Map<String,Object>)m;
        }).toList();
    }

    /**
     * Step 1 in the bank app: the customer types the FİN and the contact number. A bank that does not know them says so;
     * a customer gets an SMS with a code. (The code is returned so the wallet can show the SMS on screen; a real bank only texts it.)
     */
    @PostMapping("/anipay/bank/start") synchronized Object bankStart(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var bank=bankOf(text(b,"bankId"));String bankId=bank.get("id").toString();
        String fin=text(b,"fin").toUpperCase(Locale.ROOT),phone=phoneKey(text(b,"phone"));
        if(!fin.matches(SHORT)||phone.length()<9)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_IDENTITY");
        if(!OpenBankingSim.isCustomer(bankId,phone,fin))return Map.of("customer",false);
        otps.values().removeIf(o->bankId.equals(o.get("bankId"))&&fin.equals(o.get("fin"))&&phone.equals(o.get("phone")));
        String id=UUID.randomUUID().toString(),code=String.format("%06d",random.nextInt(1_000_000));
        var s=new LinkedHashMap<String,Object>();
        s.put("id",id);s.put("bankId",bankId);s.put("fin",fin);s.put("phone",phone);s.put("code",code);s.put("attempts",0);s.put("verified",false);s.put("expiresAt",System.currentTimeMillis()+600_000);
        otps.put(id,s);persist();
        String shown=phone.length()>6?phone.substring(0,5)+"•• ••• "+phone.substring(phone.length()-2):phone;
        return Map.of("customer",true,"sessionId",id,"bankName",bank.get("name"),"maskedPhone",shown,
            "sms",Map.of("title",bank.get("name")+" · SMS","body","AniPay təsdiq kodu: "+code+". Kodu heç kimlə paylaşmayın."));
    }

    /** Step 2: the SMS code. Three wrong tries end the session. A right one shows what is waiting and where it can land. */
    @PostMapping("/anipay/bank/verify") synchronized Object bankVerify(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var s=session(text(b,"sessionId"));
        if(!s.get("code").equals(text(b,"code"))){
            int tries=((Number)s.get("attempts")).intValue()+1;s.put("attempts",tries);
            if(tries>=3)otps.remove(s.get("id").toString());
            persist();return Map.of("verified",false,"attemptsLeft",Math.max(0,3-tries));
        }
        s.put("verified",true);persist();
        String fin=s.get("fin").toString(),phone=s.get("phone").toString();
        var waiting=transfers.values().stream().filter(t->matches(t,fin,phone)&&open(t)).map(t->{
            var m=new LinkedHashMap<String,Object>();
            m.put("transferId",t.get("transferId"));m.put("senderName",text(t,"senderName").isBlank()?"Kampus istifadəçisi":text(t,"senderName"));m.put("amountMinor",t.get("amountMinor"));
            return (Map<String,Object>)m;
        }).toList();
        return Map.of("verified",true,"transfers",waiting,"accounts",accountViews(s.get("bankId").toString(),fin));
    }

    /** Step 3: the customer picks the card or current account and confirms; the money is booked on it. */
    @PostMapping("/anipay/bank/accept") synchronized Object bankAccept(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var s=session(text(b,"sessionId"));
        if(!Boolean.TRUE.equals(s.get("verified")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"CODE_NOT_CONFIRMED");
        var t=transfers.get(text(b,"transferId"));
        if(t==null||!matches(t,s.get("fin").toString(),s.get("phone").toString())||!open(t))return Map.of("status","UNAVAILABLE");
        String bankId=s.get("bankId").toString();
        var account=accountViews(bankId,s.get("fin").toString()).stream().filter(a->a.get("id").equals(text(b,"accountId"))).findFirst().orElse(null);
        if(account==null)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"ACCOUNT_NOT_FOUND");
        long a=amount(t);String label=bankOf(bankId).get("name")+" · "+account.get("name")+" "+account.get("masked");
        banks.credit(account.get("id").toString(),a,"AniPay: "+(text(t,"senderName").isBlank()?"Kampus":text(t,"senderName")));
        t.put("status","COMPLETED");t.put("receivedAt",Instant.now().toString());t.put("receivedTo",label);
        otps.remove(s.get("id").toString());persist();
        return Map.of("status","COMPLETED","amountMinor",a,"receivedTo",label);
    }
}
