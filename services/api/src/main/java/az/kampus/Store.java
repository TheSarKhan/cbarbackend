package az.kampus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
import java.time.Instant;
@Component public class Store {
 final JdbcTemplate db; final ObjectMapper json;
 Store(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 static String id(){return UUID.randomUUID().toString();}
 static String now(){return Instant.now().toString();}
 static Map<String,Object> m(Object... args){var m=new LinkedHashMap<String,Object>();for(int i=0;i<args.length;i+=2)m.put(args[i].toString(),args[i+1]);return m;}
 String encode(Object data){try{return json.writeValueAsString(data);}catch(Exception e){throw new IllegalArgumentException(e);}}
 Map<String,Object> decode(String data){try{return json.readValue(data,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw new IllegalArgumentException(e);}}
 Map<String,Object> add(String kind,String owner,Map<String,Object> body){var b=new LinkedHashMap<>(body);b.putIfAbsent("id",id());b.putIfAbsent("createdAt",now());db.update("INSERT INTO records(id,kind,owner_id,body,created_at) VALUES(?,?,?,?,?)",b.get("id"),kind,owner,encode(b),b.get("createdAt"));return b;}
 Map<String,Object> get(String kind,String id){var r=db.query("SELECT body FROM records WHERE kind=? AND id=?",(rs,n)->decode(rs.getString(1)),kind,id);if(r.isEmpty())throw new Problem(404,"NOT_FOUND","Məlumat tapılmadı.");return r.getFirst();}
 Map<String,Object> own(String kind,String id,String owner){var r=db.queryForList("SELECT owner_id FROM records WHERE kind=? AND id=?",kind,id);if(r.isEmpty())throw new Problem(404,"NOT_FOUND","Məlumat tapılmadı.");if(!owner.equals(r.getFirst().get("owner_id")))throw new Problem(403,"FORBIDDEN","Bu məlumatı görə bilməzsiniz.");return get(kind,id);}
 void save(String kind,Map<String,Object> body){db.update("UPDATE records SET body=? WHERE kind=? AND id=?",encode(body),kind,body.get("id"));}
 List<Map<String,Object>> list(String kind,String owner){return owner==null?db.query("SELECT body FROM records WHERE kind=? ORDER BY created_at DESC",(r,n)->decode(r.getString(1)),kind):db.query("SELECT body FROM records WHERE kind=? AND owner_id=? ORDER BY created_at DESC",(r,n)->decode(r.getString(1)),kind,owner);}
 void lock(){db.queryForObject("SELECT id FROM system_lock WHERE id=1 FOR UPDATE",Integer.class);}
 void audit(String actor,String action,Object data){db.update("INSERT INTO audit_log VALUES(?,?,?,?,?)",id(),actor,action,encode(data),now());}
 static String str(Map<String,Object>b,String k){return Objects.toString(b.get(k),"");}
 static long num(Map<String,Object>b,String k){Object v=b.get(k);try{return new java.math.BigDecimal(v.toString()).longValueExact();}catch(Exception e){throw new Problem(400,"INVALID_AMOUNT",k+" tam ədəd olmalıdır.");}}
 static long amount(Map<String,Object>b){long a=num(b,"amountMinor");Problem.require(a>0&&a<=100_000_000,"INVALID_AMOUNT","Məbləğ müsbət və icazə verilən limit daxilində olmalıdır.");return a;}
 static boolean yes(Map<String,Object>b,String k){return Boolean.TRUE.equals(b.get(k));}
}
