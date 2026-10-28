package az.kampus.sim;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.time.Instant;

/** Hosted demo form. PAN is validated here and never returned to Kampus API. */
@RestController
public class CardNetwork {
    @Value("${kampus.card-state-file:.local/card-network.json}") String stateFile;
    final SimApplication app;
    @jakarta.annotation.PostConstruct void load(){try{var path=java.nio.file.Path.of(stateFile);if(java.nio.file.Files.exists(path)){var root=app.json.readTree(path.toFile());for(String key:List.of("sessions","transfers"))root.path(key).fields().forEachRemaining(e->{@SuppressWarnings("unchecked") Map<String,Object> value=app.json.convertValue(e.getValue(),Map.class);(key.equals("sessions")?sessions:transfers).put(e.getKey(),value);});}}catch(Exception e){throw new IllegalStateException("Cannot read demo card state",e);}}
    void persist(){try{var path=java.nio.file.Path.of(stateFile).toAbsolutePath();java.nio.file.Files.createDirectories(path.getParent());var temp=path.resolveSibling(path.getFileName()+".tmp");app.json.writeValue(temp.toFile(),Map.of("sessions",sessions,"transfers",transfers));java.nio.file.Files.move(temp,path,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(Exception e){throw new IllegalStateException("Cannot save demo card state",e);}}

    final Map<String,Map<String,Object>> sessions = new HashMap<>();
    final Map<String,Map<String,Object>> transfers = new HashMap<>();
    CardNetwork(SimApplication app){this.app=app;}
    static String text(Map<String,Object> b,String key){return Objects.toString(b.get(key),"");}
    static boolean luhn(String number){
        if(!number.matches("[0-9]{16}"))return false;
        int sum=0;boolean twice=false;
        for(int i=number.length()-1;i>=0;i--){int n=number.charAt(i)-'0';if(twice){n*=2;if(n>9)n-=9;}sum+=n;twice=!twice;}
        return sum%10==0;
    }
    static String bankForBin(String bin){
        if(bin.startsWith("4169"))return "Kapital Bank";
        if(bin.startsWith("4098"))return "ABB";
        if(bin.startsWith("5222"))return "PAŞA Bank";
        if(bin.startsWith("4454"))return "Leobank";
        if(bin.startsWith("4026"))return "Unibank";
        return "Mock Bank";
    }
    static String brandForPan(String pan){return pan.startsWith("5")?"MASTERCARD":"VISA";}
    Map<String,Object> session(String id){
        var s=sessions.get(id);
        if(s==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(((Number)s.get("expiresAt")).longValue()<System.currentTimeMillis())throw new ResponseStatusException(HttpStatus.GONE,"SESSION_EXPIRED");
        return s;
    }
    @PostMapping("/cards/sessions") synchronized Object create(@RequestHeader("X-Api-Key")String key){
        app.authorize(key);String id=UUID.randomUUID().toString();
        sessions.put(id,new LinkedHashMap<>(Map.of("id",id,"nonce",UUID.randomUUID().toString(),"status","PENDING","expiresAt",System.currentTimeMillis()+900000)));
        persist();return Map.of("sessionId",id,"formPath","/card-form/"+id);
    }
    /** A card typed straight into the wallet: the same checks and token as the hosted form, as an already completed session. */
    @PostMapping("/cards/tokenize") synchronized Object tokenize(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object>b){
        app.authorize(key);String pan=text(b,"pan");
        if(!pan.matches("\\d{16}")||!luhn(pan))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_PAN");
        String id=UUID.randomUUID().toString(),bin=pan.substring(0,6);var s=new LinkedHashMap<String,Object>();
        s.put("id",id);s.put("nonce",UUID.randomUUID().toString());s.put("expiresAt",System.currentTimeMillis()+900000);
        s.put("cardToken","tok_"+UUID.randomUUID());s.put("bin",bin);s.put("last4",pan.substring(12));s.put("bankName",bankForBin(bin));s.put("brand",brandForPan(pan));
        s.put("scenario",pan.equals("4000000000000002")?"DECLINED":pan.equals("4000000000009995")?"TIMEOUT":"APPROVED");s.put("status","COMPLETED");
        sessions.put(id,s);persist();
        return Map.of("sessionId",id,"bin",bin,"last4",s.get("last4"),"bankName",s.get("bankName"),"brand",s.get("brand"));
    }
    @PostMapping("/cards/session-status") synchronized Object status(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object>b){
        app.authorize(key);var s=session(text(b,"sessionId"));
        if(!"COMPLETED".equals(s.get("status")))return Map.of("status","PENDING");
        return Map.of("status","COMPLETED","cardToken",s.get("cardToken"),"bin",s.getOrDefault("bin","424242"),"last4",s.get("last4"),"bankName",s.getOrDefault("bankName","Mock Bank"),"brand",s.getOrDefault("brand","VISA"));
    }
    @GetMapping(value="/card-form/{id}",produces=MediaType.TEXT_HTML_VALUE)
    synchronized ResponseEntity<String> form(@PathVariable String id){
        var s=session(id);
        if("COMPLETED".equals(s.get("status")))return html("<h1>Kart hazırdır ✓</h1><p>"+s.getOrDefault("bankName","Mock Bank")+" · "+s.getOrDefault("brand","VISA")+" •••• "+s.get("last4")+"</p><p>Kampus tətbiqinə qayıdıb “Kartı yoxla” seçin.</p>");
        return html("""
            <span class="badge">MOCK KART ŞƏBƏKƏSİ · PCI TOKENİZASİYA</span><h1>Kartı əlavə et.</h1>
            <p>16 rəqəmli PAN yalnız bu simulyator formasında yoxlanılır (Luhn + BIN) və Kampus API-yə yalnız təhlükəsiz token qaytarılır.</p>
            <form method="post"><label>Demo kart nömrəsi (16 rəqəm)<input name="pan" inputmode="numeric" autocomplete="off" maxlength="16" pattern="[0-9]{16}" required placeholder="4242424242424242"></label>
            <input type="hidden" name="nonce" value="%s"><button>Kartı təsdiqlə</button></form>
            <section><strong>Demo ssenariləri və BIN-lər</strong><p>4242424242424242 — Mock Bank · təsdiq<br>4169738855551234 — Kapital Bank (VISA) · təsdiq<br>4098584412345678 — ABB (VISA) · təsdiq<br>5222081122334452 — PAŞA Bank (MASTERCARD) · təsdiq<br>4000000000000002 — rədd (HOLD açılır)<br>4000000000009995 — şəbəkə gecikməsi (UNDER_REVIEW)</p></section>
            """.formatted(s.get("nonce")));
    }
    @PostMapping(value="/card-form/{id}",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE,produces=MediaType.TEXT_HTML_VALUE)
    synchronized ResponseEntity<String> complete(@PathVariable String id,@RequestParam String pan,@RequestParam String nonce){
        var s=session(id);
        if(!Objects.equals(nonce,s.get("nonce")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(!luhn(pan))
            return ResponseEntity.unprocessableEntity().contentType(MediaType.TEXT_HTML).body(page("<h1>Kart qəbul edilmədi</h1><p>Luhn alqoritmi yoxlamasından keçən 16 rəqəmli demo kart nömrəsi daxil edin.</p><a href=\"/card-form/"+id+"\">Yenidən sına</a>"));
        if(!"COMPLETED".equals(s.get("status"))){
            String bin=pan.substring(0,6);
            s.put("cardToken","tok_"+UUID.randomUUID());s.put("bin",bin);s.put("last4",pan.substring(12));s.put("bankName",bankForBin(bin));s.put("brand",brandForPan(pan));
            s.put("scenario",pan.equals("4000000000000002")?"DECLINED":pan.equals("4000000000009995")?"TIMEOUT":"APPROVED");
            s.put("status","COMPLETED");persist();
        }
        return html("<h1>Kart əlavə edildi ✓</h1><p>"+s.get("bankName")+" ("+s.get("brand")+") · BIN "+s.get("bin")+" · •••• "+s.get("last4")+"</p><p>Kampus tətbiqinə qayıdıb “Kartı yoxla” seçin.</p>");
    }
    @PostMapping("/oct/submit") synchronized Object submit(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object>b){
        app.authorize(key);String id=text(b,"transferId");if(transfers.containsKey(id))return transfers.get(id);
        String token=text(b,"cardToken");var s=sessions.values().stream().filter(x->token.equals(x.get("cardToken"))).findFirst().orElse(Map.of("scenario","APPROVED"));
        long a=new java.math.BigDecimal(b.get("amountMinor").toString()).longValueExact();if(a<=0)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        var t=new LinkedHashMap<String,Object>();t.put("transferId",id);t.put("status","PENDING");t.put("scenario",s.getOrDefault("scenario","APPROVED"));t.put("readyAt",System.currentTimeMillis()+1500);transfers.put(id,t);persist();return Map.of("status","PENDING","reference",id);
    }
    @PostMapping("/oct/status") synchronized Object octStatus(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object>b){
        app.authorize(key);var t=transfers.get(text(b,"transferId"));if(t==null)return Map.of("status","UNKNOWN");
        if(((Number)t.get("readyAt")).longValue()>System.currentTimeMillis()||"TIMEOUT".equals(t.get("scenario")))return Map.of("status","PENDING");
        return Map.of("status",t.get("scenario"));
    }
    @PostMapping("/oct/demo-resolve") synchronized Object demoResolve(@RequestHeader("X-Api-Key")String key,@RequestBody Map<String,Object>b){
        app.authorize(key);var t=transfers.get(text(b,"transferId"));if(t==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String decision=text(b,"decision");if(!Set.of("APPROVED","DECLINED").contains(decision))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        if(!"TIMEOUT".equals(t.get("scenario")))throw new ResponseStatusException(HttpStatus.CONFLICT);
        t.put("scenario",decision);t.put("readyAt",0);persist();return Map.of("status",decision,"demo",true);
    }
    static ResponseEntity<String> html(String inner){return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'").contentType(MediaType.TEXT_HTML).body(page(inner));}
    static String page(String inner){return "<!doctype html><html lang=\"az\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Kampus · Mock kart forması</title><style>*{box-sizing:border-box}body{margin:0;background:#f7f8f3;color:#17352e;font:15px system-ui;padding:40px 24px}main{max-width:450px;margin:40px auto}h1{font-size:34px;letter-spacing:-1px}p{line-height:1.8;color:#7b8978}label{font-size:12px}input{display:block;padding:17px;border:1px solid #dce5d3;border-radius:12px;width:100%;margin:12px 0 20px;font-size:19px}input[type=hidden]{display:none}button{width:100%;border:0;border-radius:12px;padding:18px;background:#17352e;color:white;font-size:15px}.badge{background:#e5f0d4;padding:7px 12px;border-radius:6px;font-size:10px;letter-spacing:1px}section{padding:24px;background:#eaf0df;border-radius:15px;margin-top:30px}section p{font-size:13px}</style><main>"+inner+"</main></html>";}
}
