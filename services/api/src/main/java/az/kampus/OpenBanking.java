package az.kampus;

import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static az.kampus.Store.*;

/**
 * Open banking as the customer lives it: Kampus asks the bank, the bank app notifies its customer, the customer picks cards,
 * a period and a monthly limit, signs the consent with SİMA, and from then on Kampus can read the shared cards and pay from them.
 * Bank data lives in OB_* records and is never scored for credit or screened for fraud; payments from a card go through the bank's own limits.
 */
@Service
public class OpenBanking {
    interface Notifier{void send(String user,String title,String body);}

    static final Set<String> FOOD=Set.of("5411","5499","5812","5814"),TRANSPORT=Set.of("4111","4121","4131","5541"),
        SUBSCRIPTIONS=Set.of("4899","5968","5815","5817"),FUN=Set.of("7832","5816","7933","7994","7922"),
        SHOPPING=Set.of("5651","5311","5732","5691","5945"),UTILITIES=Set.of("4814","4900");
    final Store s;final Simulator sim;final Tokens tokens;final Auth auth;final KycMedia media;
    OpenBanking(Store s,Simulator sim,Tokens tokens,Auth auth,KycMedia media){this.s=s;this.sim=sim;this.tokens=tokens;this.auth=auth;this.media=media;}

    /** Banks send merchant category codes, not categories; Kampus maps them onto its own budget categories. */
    static final String INTERNAL="Daxili köçürmə";
    static String category(String mcc,String direction){
        if("0001".equals(mcc))return INTERNAL; // between the customer's own accounts: neither income nor spending
        if("IN".equals(direction))return "Gəlir";
        if(FOOD.contains(mcc))return "Yemək";
        if(TRANSPORT.contains(mcc))return "Nəqliyyat";
        if(SUBSCRIPTIONS.contains(mcc))return "Abunəliklər";
        if(FUN.contains(mcc))return "Əyləncə";
        if(SHOPPING.contains(mcc))return "Alış-veriş";
        if(UTILITIES.contains(mcc))return "Kommunal";
        if("6012".equals(mcc))return "Köçürmələr";
        return "Digər";
    }

    /** What a client may see of a consent or account row: the bank's own identifiers and internal media references stay on the server. */
    static Map<String,Object> clean(Map<String,Object> row){
        var copy=new LinkedHashMap<>(row);
        for(var key:List.of("simConsentId","externalId","signatureMediaId"))copy.remove(key);
        return copy;
    }

    @SuppressWarnings("unchecked") List<Map<String,Object>> banks(){return (List<Map<String,Object>>)sim.call("/openbanking/banks",m()).get("banks");}

    /** A consent whose bank-side validity has run out is marked locally; its last known data stays visible as stale. */
    void expireIfDue(Map<String,Object> consent){
        if("AUTHORIZED".equals(str(consent,"status"))&&consent.get("expiresAt")!=null&&((Number)consent.get("expiresAt")).longValue()<System.currentTimeMillis()){
            consent.put("status","EXPIRED");s.save("OB_CONSENT",consent);
        }
    }

    Map<String,Object> overview(String uid){
        var consents=new ArrayList<Map<String,Object>>();
        for(var c:s.list("OB_CONSENT",uid)){expireIfDue(c);if(!"REVOKED".equals(str(c,"status")))consents.add(c);}
        var live=new HashSet<String>();String syncedAt="";long total=0;
        for(var c:consents){
            if("AUTHORIZED".equals(str(c,"status")))live.add(str(c,"id"));
            if(str(c,"lastSyncAt").compareTo(syncedAt)>0)syncedAt=str(c,"lastSyncAt");
        }
        var accounts=new ArrayList<Map<String,Object>>();
        var known=new HashSet<String>();for(var c:consents)known.add(str(c,"id"));
        for(var a:s.list("OB_ACCOUNT",uid))if(known.contains(str(a,"consentId"))){
            accounts.add(a);
            if(live.contains(str(a,"consentId"))&&"AZN".equals(str(a,"currency")))total+=num(a,"balanceMinor");
        }
        return m("consents",consents.stream().map(OpenBanking::clean).toList(),"accounts",accounts.stream().map(OpenBanking::clean).toList(),"totalBalanceMinor",total,"syncedAt",syncedAt);
    }

    Object read(Auth.User u,String p){
        String uid=u.id();s.lock();
        if(p.equals("open-banking/banks"))return m("banks",banks());
        if(p.equals("open-banking/overview"))return overview(uid);
        if(p.equals("open-banking/transactions"))return s.list("OB_TX",uid).stream().limit(400).toList();
        String[] parts=p.split("/");
        if(parts.length==4&&parts[1].equals("consents")&&parts[3].equals("document"))return document(u,s.own("OB_CONSENT",parts[2],uid));
        if(parts.length==3&&parts[1].equals("consents")){
            var c=s.own("OB_CONSENT",parts[2],uid);
            if("AWAITING_AUTHORIZATION".equals(str(c,"status"))){
                var remote=sim.call("/openbanking/consent-status",m("consentId",c.get("simConsentId")));
                String status=str(remote,"status");
                if(!status.equals("AWAITING_AUTHORIZATION")){
                    c.put("status",status);
                    if(remote.get("expiresAt")!=null){c.put("authorizedAt",now());c.put("expiresAt",remote.get("expiresAt"));}
                    s.save("OB_CONSENT",c);
                    s.audit(uid,"OPEN_BANKING_CONSENT_"+status,m("bankId",c.get("bankId"),"consentId",c.get("id")));
                }
            }else expireIfDue(c);
            return clean(c);
        }
        throw new Problem(404,"NOT_FOUND","Səhifə tapılmadı.");
    }

    Object write(Auth.User u,String p,Map<String,Object> b,Notifier notifier){
        String uid=u.id();
        if(p.equals("open-banking/consents"))return createConsent(u,str(b,"bankId"),notifier);
        String[] parts=p.split("/");
        if(parts.length==4&&parts[1].equals("consents")){
            var c=s.own("OB_CONSENT",parts[2],uid);
            if(parts[3].equals("sync"))return sync(uid,c,notifier);
            if(parts[3].equals("revoke"))return revoke(uid,c);
            if(parts[3].equals("select"))return select(c,b);
            if(parts[3].equals("sign"))return sign(u,c,b,notifier);
        }
        throw new Problem(404,"NOT_FOUND","Səhifə tapılmadı.");
    }

    /** Step 1-2: Kampus asks the bank, which looks the person up by phone and FİN and notifies its customer in the bank app. */
    Map<String,Object> createConsent(Auth.User u,String bankId,Notifier notifier){
        String uid=u.id();
        var bank=banks().stream().filter(x->bankId.equals(x.get("id"))).findFirst().orElseThrow(()->new Problem(404,"BANK_NOT_FOUND","Bank tapılmadı."));
        for(var old:s.list("OB_CONSENT",uid))if(bankId.equals(old.get("bankId"))){
            expireIfDue(old);
            String status=str(old,"status");
            if(status.equals("AUTHORIZED"))throw new Problem(409,"ALREADY_LINKED","Bu bank artıq bağlıdır.");
            if(status.equals("AWAITING_AUTHORIZATION")){old.put("status","EXPIRED");s.save("OB_CONSENT",old);}
        }
        var kyc=s.list("KYC",uid).stream().findFirst().orElse(null);
        String fin=kyc==null?u.phone():tokens.reveal(kyc,"fin"),series=kyc==null?u.phone():tokens.reveal(kyc,"idSeries");
        var remote=sim.call("/openbanking/request",m("bankId",bankId,"userRef",uid,"phone",u.phone(),"fin",fin,"idSeries",series,"name",u.name()));
        if(!Boolean.TRUE.equals(remote.get("customer")))
            throw new Problem(404,"NOT_BANK_CUSTOMER",bank.get("name")+" sizi telefon nömrəsi və FİN ilə müştəri kimi tapa bilmədi. Başqa bank seçin.");
        @SuppressWarnings("unchecked") var note=(Map<String,Object>)remote.get("notification");
        var consent=s.add("OB_CONSENT",uid,m("bankId",bankId,"bankName",bank.get("name"),"bankColor",bank.get("color"),"bankInitials",bank.get("initials"),
            "simConsentId",remote.get("consentId"),"status","AWAITING_AUTHORIZATION","step","NOTIFIED","notification",note,
            "offeredCards",remote.get("cards"),"allowedMonths",remote.get("allowedMonths")));
        notifier.send(uid,str(note,"title"),str(note,"body"));
        s.audit(uid,"OPEN_BANKING_CONSENT_REQUESTED",m("bankId",bankId,"consentId",consent.get("id")));
        return clean(consent);
    }

    @SuppressWarnings("unchecked") List<Map<String,Object>> offered(Map<String,Object> c){return (List<Map<String,Object>>)c.getOrDefault("offeredCards",List.of());}

    /** Step 3-4: the customer picks cards, a period and a monthly limit inside the bank app. */
    Map<String,Object> select(Map<String,Object> c,Map<String,Object> b){
        Problem.require("AWAITING_AUTHORIZATION".equals(str(c,"status"))&&Set.of("NOTIFIED","SELECTED").contains(str(c,"step")),"CONSENT_NOT_OPEN","Bu sorğu artıq bağlanıb. Bankı yenidən seçin.");
        var valid=new HashSet<String>();for(var card:offered(c))valid.add(str(card,"id"));
        var chosen=new ArrayList<String>();
        if(b.get("accountIds") instanceof List<?> list)for(var id:list)if(valid.contains(String.valueOf(id))&&!chosen.contains(String.valueOf(id)))chosen.add(String.valueOf(id));
        Problem.require(!chosen.isEmpty(),"NO_CARD_SELECTED","Ən azı bir kart seçin.");
        int months;long limit;
        try{months=(int)num(b,"months");limit=num(b,"monthlyLimitMinor");}catch(Problem e){throw new Problem(422,"INVALID_TERMS","Müddət və aylıq limiti seçin.");}
        Problem.require(Set.of(1,2,3,6,12).contains(months),"INVALID_TERMS","İcazə müddəti 1, 2, 3, 6 və ya 12 ay ola bilər.");
        Problem.require(limit>=1_000&&limit<=1_000_000,"INVALID_TERMS","Aylıq limit 10 ilə 10 000 AZN arasında olmalıdır.");
        sim.call("/openbanking/select",m("consentId",c.get("simConsentId"),"accountIds",chosen,"months",months,"monthlyLimitMinor",limit));
        var actions=new ArrayList<>(List.of("TURN_LEFT","TURN_RIGHT","BLINK","SMILE"));Collections.shuffle(actions);
        c.put("accountIds",chosen);c.put("months",months);c.put("monthlyLimitMinor",limit);c.put("step","SELECTED");c.put("termsFrom",now());c.put("signActions",new ArrayList<>(actions.subList(0,3)));
        s.save("OB_CONSENT",c);
        return clean(c);
    }

    Map<String,Object> doc(Auth.User u,Map<String,Object> c,Map<String,Object> signature){
        var kyc=s.list("KYC",u.id()).stream().findFirst().orElse(null);
        var cards=offered(c).stream().filter(x->((List<?>)c.get("accountIds")).contains(str(x,"id"))).toList();
        return ConsentDocument.build(c,u.name(),kyc==null?"•••":str(kyc,"finMasked"),u.phone(),cards,Instant.parse(str(c,"termsFrom")),signature);
    }

    /** The consent as a preview (unsigned) or the signed copy; the PDF travels as base64 so the app can show and save it. */
    Map<String,Object> document(Auth.User u,Map<String,Object> c){
        Problem.require(Set.of("SELECTED","SIGNED").contains(str(c,"step")),"CONSENT_NOT_READY","Əvvəlcə kart və müddəti seçin.");
        boolean signed="SIGNED".equals(str(c,"step"));
        Map<String,Object> signature=signed?m("contractId",c.get("contractId"),"signatureHash",c.get("signatureHash"),"signedAt",ConsentDocument.DATE_TIME.format(Instant.parse(str(c,"signedAt"))),"method","SİMA İmza"):null;
        var doc=doc(u,c,signature);
        byte[] pdf=ConsentDocument.pdf(doc,str(c,"bankName"));
        return m("document",doc,"documentHash",ConsentDocument.contentHash(doc),"signed",signed,"mime","application/pdf",
            "filename","acig-bankciliq-icaze-"+str(doc,"reference").toLowerCase(Locale.ROOT)+(signed?"-imzali":"")+".pdf","dataBase64",Base64.getEncoder().encodeToString(pdf));
    }

    /** Step 5-6: the customer signs the document with SİMA (a fresh face check); the signature switches the consent on. */
    Map<String,Object> sign(Auth.User u,Map<String,Object> c,Map<String,Object> b,Notifier notifier){
        String uid=u.id();
        Problem.require("AWAITING_AUTHORIZATION".equals(str(c,"status"))&&"SELECTED".equals(str(c,"step")),"CONSENT_NOT_READY","Əvvəlcə kart və müddəti seçin.");
        byte[] selfie=auth.verifyLiveness(b,c.get("signActions"));
        String mediaId=media.store(uid,"CONSENT_SELFIE","image/jpeg",selfie);
        String hash=ConsentDocument.contentHash(doc(u,c,null));
        var sima=sim.call("/sima/sign",m("phone",u.phone(),"name",u.name(),"documentHash",hash));
        Instant from=Instant.parse(str(c,"termsFrom"));
        long expires=from.plus(Duration.ofDays(30L*num(c,"months"))).toEpochMilli();
        sim.call("/openbanking/sign",m("consentId",c.get("simConsentId"),"contractId",sima.get("contractId"),"signatureHash",sima.get("signatureHash"),"expiresAt",expires));
        c.put("status","AUTHORIZED");c.put("step","SIGNED");c.put("authorizedAt",now());c.put("signedAt",now());c.put("expiresAt",expires);
        c.put("contractId",sima.get("contractId"));c.put("signatureHash",sima.get("signatureHash"));c.put("documentHash",hash);c.put("signatureMediaId",mediaId);
        s.save("OB_CONSENT",c);
        s.audit(uid,"OPEN_BANKING_CONSENT_SIGNED",m("bankId",c.get("bankId"),"consentId",c.get("id"),"contractId",sima.get("contractId"),"documentHash",hash,"mediaId",mediaId,"selfieSha256",KycMedia.sha256(selfie)));
        notifier.send(uid,"Kampus bağlandı",c.get("bankName")+" kartları Kampus-a bağlandı.");
        return sync(uid,c,notifier);
    }

    /** Takes a charge back on the bank side and restores the card balance Kampus shows. False when the bank could not do it. */
    boolean refundCharge(String uid,String simConsentId,String chargeId,String accountId,long amount){
        try{
            var r=sim.call("/openbanking/reverse",m("consentId",simConsentId,"chargeId",chargeId));
            if(!Boolean.TRUE.equals(r.get("reversed")))return false;
            try{
                var account=s.own("OB_ACCOUNT",accountId,uid);
                long back=num(account,"balanceMinor")+amount;
                account.put("balanceMinor",back);account.put("availableMinor",back);s.save("OB_ACCOUNT",account);
            }catch(Problem gone){/* the consent was revoked meanwhile: nothing local to restore */}
            s.audit(uid,"OPEN_BANKING_CHARGE_REVERSED",m("chargeId",chargeId,"amountMinor",amount));
            return true;
        }catch(Problem e){return false;}
    }

    /** Pays out of a shared card. The bank applies the limit and balance; a refusal becomes a clear message. */
    Map<String,Object> charge(String uid,String accountId,long amount,String reference){
        var account=s.own("OB_ACCOUNT",accountId,uid);
        var consent=s.own("OB_CONSENT",str(account,"consentId"),uid);
        expireIfDue(consent);
        Problem.require("AUTHORIZED".equals(str(consent,"status"))&&"CARD".equals(str(account,"type")),"CARD_CONSENT_INACTIVE","Bu kartın icazəsi aktiv deyil. Bank kartını yenidən bağlayın.");
        var r=sim.call("/openbanking/charge",m("consentId",consent.get("simConsentId"),"accountId",account.get("externalId"),"amountMinor",amount,"reference",reference));
        if(Boolean.TRUE.equals(r.get("declined"))){
            String code=str(r,"code");
            switch(code){
                case "LIMIT_EXCEEDED"->throw new Problem(422,"CARD_LIMIT","Kartın aylıq ödəniş limiti aşılır (limit "+ConsentDocument.money(num(r,"monthlyLimitMinor"))+", bu ay istifadə "+ConsentDocument.money(num(r,"monthlySpentMinor"))+").");
                case "PAYMENTS_NOT_ALLOWED"->throw new Problem(422,"CARD_READ_ONLY","Bu kart yalnız oxumaq icazəsi ilə bağlanıb. Ödəniş üçün kartı yenidən bağlayın.");
                case "INSUFFICIENT_FUNDS"->throw new Problem(422,"CARD_INSUFFICIENT_FUNDS","Kartda kifayət qədər vəsait yoxdur.");
                default->throw new Problem(422,"CARD_DECLINED","Bank ödənişi qəbul etmədi ("+code+").");
            }
        }
        account.put("balanceMinor",r.get("balanceAfterMinor"));account.put("availableMinor",r.get("balanceAfterMinor"));s.save("OB_ACCOUNT",account);
        s.audit(uid,"OPEN_BANKING_CHARGE",m("bankId",consent.get("bankId"),"accountId",accountId,"amountMinor",amount,"chargeId",r.get("chargeId")));
        return m("chargeId",r.get("chargeId"),"simConsentId",consent.get("simConsentId"),"accountId",accountId,"cardLabel",str(consent,"bankName")+" "+str(account,"masked"));
    }

    /** Keeps every live consent fresh without the customer pressing Refresh; a bank that does not answer is retried next round. */
    void syncAll(Notifier notifier){
        var owners=new HashMap<String,String>();
        for(var row:s.db.queryForList("SELECT id,owner_id FROM records WHERE kind='OB_CONSENT'"))owners.put(row.get("id").toString(),row.get("owner_id").toString());
        for(var c:s.list("OB_CONSENT",null)){
            expireIfDue(c);
            String owner=owners.get(str(c,"id"));
            if(owner==null||!"AUTHORIZED".equals(str(c,"status")))continue;
            try{sync(owner,c,notifier);}catch(Problem e){/* next round */}
        }
    }

    /** Spend per category this month across Kampus and linked banks; budgets alert on the combined figure. */
    Map<String,Long> monthSpend(String uid,String month){
        var totals=new HashMap<String,Long>();
        for(var t:s.list("TX",uid))if("OUT".equals(str(t,"direction"))&&str(t,"createdAt").startsWith(month))totals.merge(str(t,"category"),num(t,"amountMinor"),Long::sum);
        for(var t:s.list("OB_TX",uid))if("OUT".equals(str(t,"direction"))&&!INTERNAL.equals(str(t,"category"))&&str(t,"createdAt").startsWith(month))totals.merge(str(t,"category"),num(t,"amountMinor"),Long::sum);
        return totals;
    }

    @SuppressWarnings("unchecked")
    Map<String,Object> sync(String uid,Map<String,Object> consent,Notifier notifier){
        String consentId=str(consent,"id");
        if(!"AUTHORIZED".equals(str(consent,"status")))return m("consent",clean(consent),"added",0,"synced",false);
        var accountsResponse=sim.call("/openbanking/accounts",m("consentId",consent.get("simConsentId")));
        String bankStatus=str(accountsResponse,"status");
        if(!bankStatus.equals("AUTHORIZED")){
            consent.put("status",bankStatus);s.save("OB_CONSENT",consent);
            return m("consent",clean(consent),"added",0,"synced",false);
        }
        String stamp=now();
        var ours=new HashMap<String,Map<String,Object>>();
        for(var a:s.list("OB_ACCOUNT",uid))if(consentId.equals(str(a,"consentId")))ours.put(str(a,"externalId"),a);
        for(var remote:(List<Map<String,Object>>)accountsResponse.get("accounts")){
            var row=ours.get(str(remote,"id"));
            if(row==null){
                row=s.add("OB_ACCOUNT",uid,m("consentId",consentId,"bankId",consent.get("bankId"),"bankName",consent.get("bankName"),"bankColor",consent.get("bankColor"),
                    "externalId",remote.get("id"),"type",remote.get("type"),"name",remote.get("name"),"masked",remote.get("masked"),"brand",remote.get("brand"),
                    "currency",remote.get("currency"),"balanceMinor",remote.get("balanceMinor"),"availableMinor",remote.get("availableMinor"),"syncedAt",stamp));
                ours.put(str(remote,"id"),row);
            }else{
                row.put("balanceMinor",remote.get("balanceMinor"));row.put("availableMinor",remote.get("availableMinor"));row.put("syncedAt",stamp);
                s.save("OB_ACCOUNT",row);
            }
        }

        // Re-read a short overlap so late-booked items are not missed; external ids make the insert idempotent.
        String since=str(consent,"lastSyncAt").isBlank()?"":Instant.parse(str(consent,"lastSyncAt")).minus(Duration.ofDays(3)).toString();
        var response=sim.call("/openbanking/transactions",m("consentId",consent.get("simConsentId"),"since",since));
        var seen=new HashSet<String>();
        for(var t:s.list("OB_TX",uid))if(consentId.equals(str(t,"consentId")))seen.add(str(t,"externalId"));
        String month=LocalDate.now(ZoneOffset.UTC).toString().substring(0,7);
        var before=monthSpend(uid,month);
        var added=new HashMap<String,Long>();
        int count=0;
        for(var t:(List<Map<String,Object>>)response.get("transactions")){
            String externalId=str(t,"id");
            var account=ours.get(str(t,"accountId"));
            if(account==null||!seen.add(externalId))continue;
            String direction=str(t,"direction"),mcc=str(t,"mcc"),category=category(mcc,direction);
            s.add("OB_TX",uid,m("id","ob_"+Auth.sha256(consentId+"|"+externalId).substring(0,24),"externalId",externalId,"consentId",consentId,"accountId",account.get("id"),
                "bankId",consent.get("bankId"),"bankName",consent.get("bankName"),"bankColor",consent.get("bankColor"),"bankInitials",consent.get("bankInitials"),
                "name",t.get("name"),"amountMinor",t.get("amountMinor"),"direction",direction,"category",category,"mcc",mcc,"status","COMPLETED","source","OPEN_BANKING","type","EXTERNAL",
                "createdAt",t.get("bookedAt")));
            count++;
            if("OUT".equals(direction)&&!INTERNAL.equals(category)&&str(t,"bookedAt").startsWith(month))added.merge(category,num(t,"amountMinor"),Long::sum);
        }
        for(var budget:s.list("BUDGET",uid)){
            String category=str(budget,"category");
            if(!added.containsKey(category))continue;
            long limit=num(budget,"limitMinor"),was=before.getOrDefault(category,0L),after=was+added.get(category),warn=(limit*80)/100;
            if(limit>0&&was<limit&&after>=limit)notifier.send(uid,"Büdcə limiti aşılıb (100%)",category+" üzrə aylıq büdcə limiti keçildi.");
            else if(limit>0&&was<warn&&after>=warn)notifier.send(uid,"Büdcə xəbərdarlığı (80%)",category+" büdcəsinin 80%-dən çoxu istifadə olunub.");
        }
        consent.put("lastSyncAt",stamp);s.save("OB_CONSENT",consent);
        s.audit(uid,"OPEN_BANKING_SYNC",m("bankId",consent.get("bankId"),"consentId",consentId,"added",count));
        return m("consent",clean(consent),"added",count,"synced",true);
    }

    /** Revoking removes everything fetched under the consent; the bank is told as well. */
    Map<String,Object> revoke(String uid,Map<String,Object> consent){
        String consentId=str(consent,"id");
        boolean remote=true;
        try{sim.call("/openbanking/revoke",m("consentId",consent.get("simConsentId")));}catch(Problem e){remote=false;}
        for(var kind:List.of("OB_TX","OB_ACCOUNT"))
            for(var row:s.list(kind,uid))if(consentId.equals(str(row,"consentId")))s.db.update("DELETE FROM records WHERE kind=? AND id=?",kind,row.get("id"));
        consent.put("status","REVOKED");consent.put("revokedAt",now());consent.put("remoteRevoked",remote);
        s.save("OB_CONSENT",consent);
        s.audit(uid,"OPEN_BANKING_REVOKED",m("bankId",consent.get("bankId"),"consentId",consentId,"remoteRevoked",remote));
        return clean(consent);
    }
}
