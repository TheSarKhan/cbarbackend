package az.kampus.sim;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * AniPay between banks. A bank customer opens AniPay in their own bank app (simulated in the wallet, like the open-banking bank
 * app) and sets it up: FİN and contact number, an SMS code, then the card or current account that receives the money. From then on
 * a transfer addressed to that FİN or number is booked on that account at once.
 */
@RestController
public class AniPaySim {
    static final String SHORT="[A-Z0-9]{7}";
    @Value("${kampus.anipay-state-file:.local/anipay.json}") String stateFile;
    final SimApplication app;final OpenBankingSim banks;
    final SecureRandom random=new SecureRandom();
    /** "fin:5ABC123" or "phone:+994551234567" to where the money lands. */
    final Map<String,Map<String,Object>> registry=new LinkedHashMap<>(),transfers=new LinkedHashMap<>(),sessions=new LinkedHashMap<>();
    AniPaySim(SimApplication app,OpenBankingSim banks){this.app=app;this.banks=banks;}

    @jakarta.annotation.PostConstruct void load(){
        try{
            var path=java.nio.file.Path.of(stateFile);
            if(!java.nio.file.Files.exists(path))return;
            var root=app.json.readTree(path.toFile());
            for(var kind:List.of("registry","transfers","sessions")){
                var target=kind.equals("registry")?registry:kind.equals("transfers")?transfers:sessions;
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
            app.json.writeValue(temp.toFile(),Map.of("registry",registry,"transfers",transfers,"sessions",sessions));
            java.nio.file.Files.move(temp,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }catch(Exception ignored){}
    }
    static String text(Map<String,Object> b,String key){return Objects.toString(b.get(key),"").trim();}
    static String phoneKey(String phone){return phone.replaceAll("[^0-9+]","");}
    /** The sender names the recipient by FİN or by phone, one of the two. */
    static String keyOf(Map<String,Object> b){
        String fin=text(b,"fin").toUpperCase(Locale.ROOT),phone=phoneKey(text(b,"phone"));
        if(fin.isBlank()==phone.isBlank()||(!fin.isBlank()&&!fin.matches(SHORT)))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_RECIPIENT");
        return fin.isBlank()?"phone:"+phone:"fin:"+fin;
    }

    // ---- Kampus side: who is on AniPay, and the transfer itself -----------------

    @PostMapping("/anipay/lookup") synchronized Object lookup(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var r=registry.get(keyOf(b));
        return r==null?Map.of("registered",false):Map.of("registered",true,"bankName",r.get("bankName"));
    }
    @PostMapping("/anipay/transfers") synchronized Object submit(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        String id=text(b,"transferId");
        if(transfers.containsKey(id))return transfers.get(id);
        long a;try{a=new java.math.BigDecimal(b.get("amountMinor").toString()).longValueExact();}catch(Exception e){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_AMOUNT");}
        if(id.isBlank()||a<=0)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_TRANSFER");
        var r=registry.get(keyOf(b));
        if(r==null)return Map.of("status","REJECTED","code","RECIPIENT_NOT_REGISTERED");
        banks.credit(r.get("accountId").toString(),a,"AniPay: "+(text(b,"senderName").isBlank()?"Kampus":text(b,"senderName")));
        var out=new LinkedHashMap<String,Object>(Map.of("status","COMPLETED","reference",id,"receivedTo",r.get("accountLabel")));
        transfers.put(id,out);persist();
        return out;
    }

    // ---- the customer's bank app: setting AniPay up -----------------------------

    Map<String,Object> bankOf(String id){return OpenBankingSim.bank(id.toUpperCase(Locale.ROOT));}
    Map<String,Object> session(String id){
        var s=sessions.get(id);
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
     * Step 1: the customer types the FİN and the contact number. A bank that does not know them says so; a customer gets an SMS
     * with a code. (The code is returned so the wallet can show the SMS on screen; a real bank only texts it.)
     */
    @PostMapping("/anipay/bank/start") synchronized Object bankStart(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var bank=bankOf(text(b,"bankId"));String bankId=bank.get("id").toString();
        String fin=text(b,"fin").toUpperCase(Locale.ROOT),phone=phoneKey(text(b,"phone"));
        if(!fin.matches(SHORT)||phone.length()<9)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_IDENTITY");
        if(!OpenBankingSim.isCustomer(bankId,phone,fin))return Map.of("customer",false);
        sessions.values().removeIf(o->bankId.equals(o.get("bankId"))&&fin.equals(o.get("fin"))&&phone.equals(o.get("phone")));
        String id=UUID.randomUUID().toString(),code=String.format("%06d",random.nextInt(1_000_000));
        var s=new LinkedHashMap<String,Object>();
        s.put("id",id);s.put("bankId",bankId);s.put("fin",fin);s.put("phone",phone);s.put("code",code);s.put("attempts",0);s.put("verified",false);s.put("expiresAt",System.currentTimeMillis()+600_000);
        sessions.put(id,s);persist();
        String shown=phone.length()>6?phone.substring(0,5)+"•• ••• "+phone.substring(phone.length()-2):phone;
        return Map.of("customer",true,"sessionId",id,"bankName",bank.get("name"),"maskedPhone",shown,
            "sms",Map.of("title",bank.get("name")+" · SMS","body","AniPay təsdiq kodu: "+code+". Kodu heç kimlə paylaşmayın."));
    }

    /** Step 2: the SMS code. Three wrong tries end the session. A right one shows the accounts that can receive and the current choice. */
    @PostMapping("/anipay/bank/verify") synchronized Object bankVerify(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var s=session(text(b,"sessionId"));
        if(!s.get("code").equals(text(b,"code"))){
            int tries=((Number)s.get("attempts")).intValue()+1;s.put("attempts",tries);
            if(tries>=3)sessions.remove(s.get("id").toString());
            persist();return Map.of("verified",false,"attemptsLeft",Math.max(0,3-tries));
        }
        s.put("verified",true);persist();
        var current=registry.get("fin:"+s.get("fin"));
        var out=new LinkedHashMap<String,Object>();
        out.put("verified",true);out.put("accounts",accountViews(s.get("bankId").toString(),s.get("fin").toString()));
        out.put("current",current==null?"":current.get("accountLabel"));
        return out;
    }

    /** Step 3: the customer picks the card or current account; transfers to this FİN and number land there from now on. */
    @PostMapping("/anipay/bank/register") synchronized Object bankRegister(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object> b){
        app.authorize(key);
        var s=session(text(b,"sessionId"));
        if(!Boolean.TRUE.equals(s.get("verified")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"CODE_NOT_CONFIRMED");
        String bankId=s.get("bankId").toString(),fin=s.get("fin").toString(),phone=s.get("phone").toString();
        var account=accountViews(bankId,fin).stream().filter(a->a.get("id").equals(text(b,"accountId"))).findFirst().orElse(null);
        if(account==null)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"ACCOUNT_NOT_FOUND");
        var bank=bankOf(bankId);String label=bank.get("name")+" · "+account.get("name")+" "+account.get("masked");
        var reg=new LinkedHashMap<String,Object>();
        reg.put("bankId",bankId);reg.put("bankName",bank.get("name"));reg.put("accountId",account.get("id"));reg.put("accountLabel",label);
        registry.put("fin:"+fin,reg);registry.put("phone:"+phone,reg);
        sessions.remove(s.get("id").toString());persist();
        return Map.of("registered",true,"accountLabel",label);
    }
}
