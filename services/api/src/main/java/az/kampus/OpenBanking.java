package az.kampus;

import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static az.kampus.Store.*;

/**
 * Read-only open-banking aggregation: the customer authorises on the bank's own page, Kampus pulls balances and booked
 * transactions. Everything lives in OB_* records and is never posted to the ledger, scored for credit or screened for fraud.
 */
@Service
public class OpenBanking {
    interface Notifier{void send(String user,String title,String body);}

    static final Set<String> FOOD=Set.of("5411","5499","5812","5814"),TRANSPORT=Set.of("4111","4121","4131","5541"),
        SUBSCRIPTIONS=Set.of("4899","5968","5815","5817"),FUN=Set.of("7832","5816","7933","7994","7922"),
        SHOPPING=Set.of("5651","5311","5732","5691","5945"),UTILITIES=Set.of("4814","4900");
    final Store s;final Simulator sim;
    OpenBanking(Store s,Simulator sim){this.s=s;this.sim=sim;}

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
        return m("consents",consents,"accounts",accounts,"totalBalanceMinor",total,"syncedAt",syncedAt);
    }

    Object read(Auth.User u,String p){
        String uid=u.id();s.lock();
        if(p.equals("open-banking/banks"))return m("banks",banks());
        if(p.equals("open-banking/overview"))return overview(uid);
        if(p.equals("open-banking/transactions"))return s.list("OB_TX",uid).stream().limit(400).toList();
        String[] parts=p.split("/");
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
            return c;
        }
        throw new Problem(404,"NOT_FOUND","Səhifə tapılmadı.");
    }

    Object write(Auth.User u,String p,Map<String,Object> b,Notifier notifier){
        String uid=u.id();
        if(p.equals("open-banking/consents"))return createConsent(uid,str(b,"bankId"));
        String[] parts=p.split("/");
        if(parts.length==4&&parts[1].equals("consents")){
            var c=s.own("OB_CONSENT",parts[2],uid);
            if(parts[3].equals("sync"))return sync(uid,c,notifier);
            if(parts[3].equals("revoke"))return revoke(uid,c);
        }
        throw new Problem(404,"NOT_FOUND","Səhifə tapılmadı.");
    }

    Map<String,Object> createConsent(String uid,String bankId){
        var bank=banks().stream().filter(x->bankId.equals(x.get("id"))).findFirst().orElseThrow(()->new Problem(404,"BANK_NOT_FOUND","Bank tapılmadı."));
        for(var old:s.list("OB_CONSENT",uid))if(bankId.equals(old.get("bankId"))){
            expireIfDue(old);
            String status=str(old,"status");
            if(status.equals("AUTHORIZED"))throw new Problem(409,"ALREADY_LINKED","Bu bank artıq bağlıdır.");
            if(status.equals("AWAITING_AUTHORIZATION")){old.put("status","EXPIRED");s.save("OB_CONSENT",old);}
        }
        var remote=sim.call("/openbanking/consents",m("bankId",bankId,"userRef",uid));
        var consent=s.add("OB_CONSENT",uid,m("bankId",bankId,"bankName",bank.get("name"),"bankColor",bank.get("color"),"bankInitials",bank.get("initials"),
            "simConsentId",remote.get("consentId"),"authorizationPath",remote.get("authorizationPath"),"status","AWAITING_AUTHORIZATION"));
        s.audit(uid,"OPEN_BANKING_CONSENT_REQUESTED",m("bankId",bankId,"consentId",consent.get("id")));
        return consent;
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
        if(!"AUTHORIZED".equals(str(consent,"status")))return m("consent",consent,"added",0,"synced",false);
        var accountsResponse=sim.call("/openbanking/accounts",m("consentId",consent.get("simConsentId")));
        String bankStatus=str(accountsResponse,"status");
        if(!bankStatus.equals("AUTHORIZED")){
            consent.put("status",bankStatus);s.save("OB_CONSENT",consent);
            return m("consent",consent,"added",0,"synced",false);
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
        return m("consent",consent,"added",count,"synced",true);
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
        return consent;
    }
}
