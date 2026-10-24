package az.kampus;
import org.springframework.stereotype.Service;
import java.util.*;
import static az.kampus.Store.*;
@Service public class Ledger {
 final Store s;
 Ledger(Store s){this.s=s;}
 void account(String id,String normal){if(s.db.queryForObject("SELECT COUNT(*) FROM accounts WHERE id=?",Long.class,id)==0)s.db.update("INSERT INTO accounts VALUES(?,?)",id,normal);}
 long balance(String account){Long v=s.db.queryForObject("SELECT COALESCE(SUM(CASE WHEN p.direction=a.normal_side THEN p.amount ELSE -p.amount END),0) FROM accounts a LEFT JOIN postings p ON a.id=p.account_id WHERE a.id=?",Long.class,account);return v==null?0:v;}
 long held(String account){return s.db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM holds WHERE account_id=? AND status='ACTIVE'",Long.class,account);}
 long available(String account){return balance(account)-held(account);}
 String hold(String account,long amount){Problem.require(available(account)>=amount,"INSUFFICIENT_FUNDS","Balans kifayət etmir.");String id=id();s.db.update("INSERT INTO holds VALUES(?,?,?,'ACTIVE')",id,account,amount);return id;}
 void release(String id){s.db.update("UPDATE holds SET status='RELEASED' WHERE id=? AND status='ACTIVE'",id);}
 void post(String type,String description,String debit,String credit,long amount){post(type,description,List.of(m("account",debit,"side","D","amount",amount),m("account",credit,"side","C","amount",amount)));}
 void post(String type,String description,List<Map<String,Object>> lines){long d=0,c=0;for(var line:lines){long a=num(line,"amount");Problem.require(a>0,"INVALID_POSTING","Yazılış məbləği yanlışdır.");if(str(line,"side").equals("D"))d=Math.addExact(d,a);else c=Math.addExact(c,a);}Problem.require(d==c,"UNBALANCED_JOURNAL","Ledger yazılışı balanslı deyil.");String j=id();s.db.update("INSERT INTO journal VALUES(?,?,?,?)",j,type,description,now());for(var line:lines)s.db.update("INSERT INTO postings VALUES(?,?,?,?,?)",id(),j,line.get("account"),line.get("side"),line.get("amount"));for(var line:lines){String a=str(line,"account");if(a.startsWith("wallet:")||a.startsWith("merchant:"))Problem.require(available(a)>=0,"INSUFFICIENT_FUNDS","Balans kifayət etmir.");}}
 Map<String,Object> wallet(String user){String a="wallet:"+user;return m("currency","AZN","ledgerBalanceMinor",balance(a),"heldAmountMinor",held(a),"availableBalanceMinor",available(a));}
 Map<String,Object> reconcile(){long wallets=s.db.queryForList("SELECT id FROM accounts WHERE id LIKE 'wallet:%' OR id LIKE 'merchant:%' OR id='CLOSED_ACCOUNT_FUNDS'").stream().mapToLong(r->balance(r.get("id").toString())).sum();long bank=balance("CUSTOMER_FUNDS_BANK");return m("walletsTotalMinor",wallets,"bankBalanceMinor",bank,"differenceMinor",bank-wallets,"status",wallets==bank?"MATCHED":"MISMATCH");}
}
