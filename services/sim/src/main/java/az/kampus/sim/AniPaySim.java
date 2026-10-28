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
 * it waits there until the recipient opens AniPay in their own bank app (the hosted pages below), picks the card or current
 * account that should receive it and confirms with an SMS code. Unclaimed transfers expire and Kampus takes the money back.
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
    static String esc(String v){return v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    static String money(long minor){return String.format(Locale.ROOT,"%d.%02d ₼",minor/100,minor%100);}
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

    // ---- the recipient's bank app -------------------------------------------

    static ResponseEntity<String> html(String body){
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff")
            .header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'self'; frame-ancestors 'none'")
            .contentType(MediaType.TEXT_HTML).body(body);
    }
    static String shell(Map<String,Object> bank,String title,String inner){
        String color=bank==null?"#2a0a8a":bank.get("color").toString(),name=bank==null?"Bank proqramı":bank.get("name").toString(),initials=bank==null?"B":bank.get("initials").toString();
        return "<!doctype html><html lang=\"az\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"+esc(name)+" · AniPay</title><style>"
            +"*{box-sizing:border-box}body{margin:0;background:#e9e7f2;font:15px system-ui,-apple-system,sans-serif;color:#1b1838}"
            +".app{max-width:430px;min-height:100vh;margin:0 auto;background:#fff;box-shadow:0 0 40px rgba(20,10,60,.15)}"
            +"header{background:"+color+";color:#fff;padding:22px 20px 26px;display:flex;align-items:center;gap:12px}"
            +".logo{width:42px;height:42px;border-radius:13px;background:rgba(255,255,255,.2);display:flex;align-items:center;justify-content:center;font-weight:800}"
            +"header small{display:block;opacity:.75;font-size:12px}header b{font-size:17px}"
            +"main{padding:22px 20px 40px}h1{font-size:22px;margin:0 0 6px}p.lead{color:#76738a;margin:0 0 18px;line-height:1.5}"
            +"label{display:block;font-size:12px;font-weight:700;color:#76738a;margin:14px 0 6px}"
            +"input[type=text],input[type=tel]{width:100%;font-size:17px;padding:14px;border:1.5px solid #dedaeb;border-radius:14px;background:#fff}"
            +"button,.btn{display:block;width:100%;margin-top:18px;padding:15px;border:0;border-radius:14px;background:"+color+";color:#fff;font-weight:800;font-size:16px;text-align:center;text-decoration:none;cursor:pointer}"
            +"button.alt,.btn.alt{background:#efecf8;color:#1b1838}"
            +".row{display:flex;align-items:center;gap:14px;padding:14px;border:1.5px solid #ebe8f4;border-radius:16px;margin-bottom:10px;background:#fff;text-decoration:none;color:inherit;width:100%;text-align:left;font-weight:400;font-size:15px;margin-top:0}"
            +".row b{display:block}.row span{color:#76738a;font-size:13px}.row .amt{margin-left:auto;font-weight:800;font-size:16px;white-space:nowrap}"
            +"label.pick{display:block;margin:0 0 10px;color:#1b1838;font-size:15px;font-weight:400}.pick input{position:absolute;opacity:0}.pick .row{margin:0}.pick input:checked+.row{border-color:"+color+";background:#f6f4ff}"
            +".tile{width:44px;height:44px;border-radius:14px;background:#efecf8;display:flex;align-items:center;justify-content:center;font-weight:800;color:"+color+"}"
            +".sms{background:#fff7d6;border:1px solid #f0d87a;border-radius:14px;padding:12px 14px;margin:0 0 16px;font-size:13px;line-height:1.5}.sms b{font-size:20px;letter-spacing:4px}"
            +".ok{text-align:center;padding:30px 0}.ok .mark{width:72px;height:72px;border-radius:24px;background:#dff5e6;color:#2f9e63;font-size:38px;font-weight:800;display:inline-flex;align-items:center;justify-content:center}"
            +".err{background:#fde3e3;color:#a63232;border-radius:14px;padding:12px 14px;margin-bottom:14px}.note{color:#76738a;font-size:12px;text-align:center;margin-top:22px}"
            +"</style><div class=\"app\"><header><div class=\"logo\">"+esc(initials)+"</div><div><small>Bank proqramı · simulyasiya</small><b>"+esc(name)+"</b></div></header><main>"+inner+"</main></div></html>";
    }
    Map<String,Object> bankOf(String id){return OpenBankingSim.bank(id.toUpperCase(Locale.ROOT));}
    static String back(String href,String label){return "<a class=\"btn alt\" href=\""+href+"\">"+label+"</a>";}

    @GetMapping(value="/bank-app",produces=MediaType.TEXT_HTML_VALUE) ResponseEntity<String> pickBank(){
        var sb=new StringBuilder("<h1>Hansı bankın proqramı?</h1><p class=\"lead\">AniPay köçürməsini alan şəxs öz bankının proqramını açır. Bank seçin:</p>");
        for(var b:OpenBankingSim.BANKS)sb.append("<a class=\"row\" href=\"/bank-app/").append(b.get("id")).append("\"><div class=\"tile\" style=\"color:").append(b.get("color")).append("\">").append(b.get("initials")).append("</div><div><b>").append(esc(b.get("name").toString())).append("</b><span>Mobil bank</span></div></a>");
        return html(shell(null,"Bank",sb.toString()));
    }
    @GetMapping(value="/bank-app/{bank}",produces=MediaType.TEXT_HTML_VALUE) ResponseEntity<String> home(@PathVariable String bank){
        var b=bankOf(bank);String id=b.get("id").toString();
        String inner="<h1>Ödənişlər</h1><p class=\"lead\">Xidmət seçin.</p>"
            +"<a class=\"row\" href=\"/bank-app/"+id+"/anipay\"><div class=\"tile\">A</div><div><b>AniPay</b><span>Pul qəbul et və göndər</span></div></a>"
            +"<div class=\"row\" style=\"opacity:.45\"><div class=\"tile\">K</div><div><b>Kommunal ödənişlər</b><span>Bu simulyasiyada əlçatmazdır</span></div></div>"
            +"<div class=\"row\" style=\"opacity:.45\"><div class=\"tile\">M</div><div><b>Mobil əlaqə</b><span>Bu simulyasiyada əlçatmazdır</span></div></div>"
            +back("/bank-app","Başqa bank seç");
        return html(shell(b,"Ödənişlər",inner));
    }
    String claimForm(Map<String,Object> bank,String error){
        String id=bank.get("id").toString();
        return "<h1>AniPay ilə qəbul</h1><p class=\"lead\">Sizə köçürmə göndərilibsə, FİN kodunuzu və əlaqə nömrənizi daxil edin. Köçürmə göndərənin yazdığı FİN və ya nömrə ilə tapılır.</p>"
            +(error.isBlank()?"":"<div class=\"err\">"+esc(error)+"</div>")
            +"<form method=\"post\" action=\"/bank-app/"+id+"/anipay\"><label>FİN kod</label><input type=\"text\" name=\"fin\" maxlength=\"7\" autocomplete=\"off\" autocapitalize=\"characters\" placeholder=\"5ABC123\" required>"
            +"<label>Əlaqə nömrəsi</label><input type=\"tel\" name=\"phone\" placeholder=\"+994 55 000 00 00\" required><button>İrəli</button></form>"+back("/bank-app/"+id,"Geri");
    }
    @GetMapping(value="/bank-app/{bank}/anipay",produces=MediaType.TEXT_HTML_VALUE) ResponseEntity<String> claim(@PathVariable String bank){
        var b=bankOf(bank);return html(shell(b,"AniPay",claimForm(b,"")));
    }
    List<Map<String,Object>> waiting(String fin,String phone){
        return transfers.values().stream().filter(t->matches(t,fin,phone)&&open(t)).toList();
    }
    @PostMapping(value="/bank-app/{bank}/anipay",produces=MediaType.TEXT_HTML_VALUE) synchronized ResponseEntity<String> find(@PathVariable String bank,@RequestParam String fin,@RequestParam String phone){
        var b=bankOf(bank);String id=b.get("id").toString();fin=fin.trim().toUpperCase(Locale.ROOT);phone=phoneKey(phone);
        if(!fin.matches(SHORT)||phone.length()<9)return html(shell(b,"AniPay",claimForm(b,"FİN kod 7 simvol, əlaqə nömrəsi isə +994 ilə başlayan nömrə olmalıdır.")));
        if(!OpenBankingSim.isCustomer(id,phone,fin))return html(shell(b,"AniPay",claimForm(b,"Bu FİN və nömrə ilə "+b.get("name")+" müştərisi tapılmadı. Başqa bankın proqramını sınayın.")));
        var list=waiting(fin,phone);
        var sb=new StringBuilder("<h1>Gözləyən köçürmələr</h1>");
        if(list.isEmpty())sb.append("<p class=\"lead\">Bu FİN və nömrə üçün gözləyən AniPay köçürməsi yoxdur.</p>").append(back("/bank-app/"+id+"/anipay","Yenidən yoxla"));
        else{
            sb.append("<p class=\"lead\">Aşağıdakı köçürməni qəbul etmək üçün seçin.</p>");
            for(var t:list)sb.append("<form method=\"post\" action=\"/bank-app/").append(id).append("/anipay/accounts\"><input type=\"hidden\" name=\"fin\" value=\"").append(fin).append("\"><input type=\"hidden\" name=\"phone\" value=\"").append(esc(phone)).append("\"><input type=\"hidden\" name=\"transferId\" value=\"").append(esc(t.get("transferId").toString())).append("\">")
                .append("<button class=\"row\"><div class=\"tile\">₼</div><div><b>").append(esc(text(t,"senderName").isBlank()?"Kampus istifadəçisi":text(t,"senderName"))).append("</b><span>AniPay köçürməsi</span></div><div class=\"amt\">").append(money(amount(t))).append("</div></button></form>");
            sb.append(back("/bank-app/"+id,"Ana səhifə"));
        }
        return html(shell(b,"AniPay",sb.toString()));
    }
    /** A transfer addressed by FİN is found by the FİN, one addressed by phone by the phone. */
    static boolean matches(Map<String,Object> t,String fin,String phone){
        return text(t,"fin").isBlank()?phone.equals(text(t,"phone")):fin.equals(text(t,"fin"));
    }
    Map<String,Object> pendingFor(String transferId,String fin,String phone){
        var t=transfers.get(transferId);
        if(t==null||!matches(t,fin,phone)||!open(t))throw new ResponseStatusException(HttpStatus.GONE,"TRANSFER_NOT_AVAILABLE");
        return t;
    }
    @PostMapping(value="/bank-app/{bank}/anipay/accounts",produces=MediaType.TEXT_HTML_VALUE) synchronized ResponseEntity<String> accounts(@PathVariable String bank,@RequestParam String fin,@RequestParam String phone,@RequestParam String transferId){
        var b=bankOf(bank);String id=b.get("id").toString();fin=fin.trim().toUpperCase(Locale.ROOT);phone=phoneKey(phone);
        var t=pendingFor(transferId,fin,phone);
        var sb=new StringBuilder("<h1>Hansı hesaba alasınız?</h1><p class=\"lead\">").append(money(amount(t))).append(" qəbul ediləcək hesabı və ya kartı seçin.</p>")
            .append("<form method=\"post\" action=\"/bank-app/").append(id).append("/anipay/otp\"><input type=\"hidden\" name=\"fin\" value=\"").append(fin).append("\"><input type=\"hidden\" name=\"phone\" value=\"").append(esc(phone)).append("\"><input type=\"hidden\" name=\"transferId\" value=\"").append(esc(transferId)).append("\">");
        boolean first=true;
        for(var a:banks.accounts(id,"fin:"+fin)){
            sb.append("<label class=\"pick\"><input type=\"radio\" name=\"accountId\" value=\"").append(esc(a.id())).append("\"").append(first?" checked":"").append("><div class=\"row\"><div class=\"tile\">").append(a.type().equals("CARD")?"💳":"₼").append("</div><div><b>").append(esc(a.name())).append("</b><span>").append(esc(a.masked())).append("</span></div><div class=\"amt\">").append(money(banks.balance(a,Instant.now()))).append("</div></div></label>");
            first=false;
        }
        sb.append("<button>İrəli</button></form>").append(back("/bank-app/"+id+"/anipay","Ləğv et"));
        return html(shell(b,"AniPay",sb.toString()));
    }
    @PostMapping(value="/bank-app/{bank}/anipay/otp",produces=MediaType.TEXT_HTML_VALUE) synchronized ResponseEntity<String> sendOtp(@PathVariable String bank,@RequestParam String fin,@RequestParam String phone,@RequestParam String transferId,@RequestParam String accountId){
        var b=bankOf(bank);String id=b.get("id").toString();fin=fin.trim().toUpperCase(Locale.ROOT);phone=phoneKey(phone);
        var t=pendingFor(transferId,fin,phone);
        var account=banks.accounts(id,"fin:"+fin).stream().filter(a->a.id().equals(accountId)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"ACCOUNT_NOT_FOUND"));
        otps.values().removeIf(o->transferId.equals(o.get("transferId")));
        String otpId=UUID.randomUUID().toString(),code=String.format("%06d",random.nextInt(1_000_000));
        var o=new LinkedHashMap<String,Object>();
        o.put("id",otpId);o.put("code",code);o.put("transferId",transferId);o.put("bankId",id);o.put("accountId",account.id());o.put("accountLabel",b.get("name")+" · "+account.name()+" "+account.masked());
        o.put("attempts",0);o.put("expiresAt",System.currentTimeMillis()+300_000);
        otps.put(otpId,o);persist();
        String shownPhone=phone.length()>6?phone.substring(0,5)+"•• ••• "+phone.substring(phone.length()-2):phone;
        String inner="<h1>SMS təsdiqi</h1><p class=\"lead\">"+money(amount(t))+" məbləğini "+esc(account.name())+" "+esc(account.masked())+" hesabına qəbul etmək üçün SMS kodunu daxil edin.</p>"
            +"<div class=\"sms\">📱 "+esc(shownPhone)+" nömrəsinə SMS göndərildi.<br>Demo kod: <b>"+code+"</b></div>"
            +"<form method=\"post\" action=\"/bank-app/"+id+"/anipay/confirm\"><input type=\"hidden\" name=\"otpId\" value=\""+otpId+"\"><label>SMS kodu</label><input type=\"text\" name=\"code\" inputmode=\"numeric\" maxlength=\"6\" autocomplete=\"off\" placeholder=\"6 rəqəm\" required><button>Təsdiqlə və qəbul et</button></form>"
            +back("/bank-app/"+id+"/anipay","Ləğv et");
        return html(shell(b,"SMS",inner));
    }
    @PostMapping(value="/bank-app/{bank}/anipay/confirm",produces=MediaType.TEXT_HTML_VALUE) synchronized ResponseEntity<String> confirm(@PathVariable String bank,@RequestParam String otpId,@RequestParam String code){
        var b=bankOf(bank);String id=b.get("id").toString();
        var o=otps.get(otpId);
        if(o==null||((Number)o.get("expiresAt")).longValue()<System.currentTimeMillis())return html(shell(b,"AniPay","<div class=\"err\">SMS kodunun vaxtı bitib. Yenidən başlayın.</div>"+back("/bank-app/"+id+"/anipay","Yenidən başla")));
        var t=transfers.get(o.get("transferId").toString());
        int attempts=((Number)o.get("attempts")).intValue();
        if(!o.get("code").equals(code.trim())){
            o.put("attempts",attempts+1);
            if(attempts+1>=3){otps.remove(otpId);persist();return html(shell(b,"AniPay","<div class=\"err\">Kod 3 dəfə yanlış daxil edildi. Yenidən başlayın.</div>"+back("/bank-app/"+id+"/anipay","Yenidən başla")));}
            persist();
            return html(shell(b,"SMS","<div class=\"err\">Kod yanlışdır. "+(2-attempts)+" cəhd qalıb.</div><form method=\"post\" action=\"/bank-app/"+id+"/anipay/confirm\"><input type=\"hidden\" name=\"otpId\" value=\""+otpId+"\"><label>SMS kodu</label><input type=\"text\" name=\"code\" inputmode=\"numeric\" maxlength=\"6\" autocomplete=\"off\" required><button>Təsdiqlə və qəbul et</button></form>"));
        }
        if(t==null||!open(t)){otps.remove(otpId);persist();return html(shell(b,"AniPay","<div class=\"err\">Bu köçürmə artıq qəbul edilib və ya vaxtı bitib.</div>"+back("/bank-app/"+id,"Ana səhifə")));}
        long a=amount(t);
        banks.credit(o.get("accountId").toString(),a,"AniPay: "+(text(t,"senderName").isBlank()?"Kampus":text(t,"senderName")));
        t.put("status","COMPLETED");t.put("receivedAt",Instant.now().toString());t.put("receivedTo",o.get("accountLabel"));
        otps.remove(otpId);persist();
        String inner="<div class=\"ok\"><div class=\"mark\">✓</div><h1 style=\"margin-top:16px\">Köçürmə qəbul edildi</h1><p class=\"lead\">"+money(a)+" məbləği<br><b>"+esc(o.get("accountLabel").toString())+"</b><br>hesabına köçürüldü.</p></div>"+back("/bank-app/"+id,"Ana səhifə");
        return html(shell(b,"Qəbul edildi",inner));
    }
}
