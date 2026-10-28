package az.kampus.sim;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import java.util.*;
import java.math.*;
@SpringBootApplication @RestController public class SimApplication {
 @Value("${kampus.sim-key}") String key;
 final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
 public static void main(String[] args){SpringApplication.run(SimApplication.class,args);}
 void authorize(String supplied){if(!java.security.MessageDigest.isEqual(key.getBytes(),supplied.getBytes()))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED);}
 long n(Map<String,Object>b,String k){return new BigDecimal(b.get(k).toString()).longValueExact();}
 @GetMapping("/health") Object health(){return Map.of("status","UP","demo",true);}
 @PostMapping("/registry/lookup") Object registry(@RequestHeader("X-Api-Key")String k,@RequestBody Map<String,Object>b){authorize(k);String fin=Objects.toString(b.get("fin"),"7AZE001").trim().toUpperCase(Locale.ROOT);boolean minor="17YASLI".equals(fin);boolean lowFace="LOWFACE".equals(fin);String birth=minor?"2010-05-10":Objects.toString(b.get("birthDate"),"2003-03-15");if(birth.isBlank())birth="2003-03-15";return Map.of("fin",fin,"maskedName",minor?"C*** M***":"Tələbə İ.", "birthDate",birth,"faceMatchScore",lowFace?0.62:0.96,"registryRef","REG-"+fin,"demo",true);}
 @PostMapping("/liveness/challenge") Object livenessChallenge(@RequestHeader("X-Api-Key")String k){authorize(k);var actions=new ArrayList<>(List.of("TURN_LEFT","TURN_RIGHT","BLINK","SMILE"));Collections.shuffle(actions);return Map.of("challengeId",UUID.randomUUID().toString(),"actions",actions.subList(0,3),"expiresAt",System.currentTimeMillis()+60_000,"demo",true);}
 @PostMapping("/sima/sign") Object simaSign(@RequestHeader("X-Api-Key")String k,@RequestBody Map<String,Object>b){authorize(k);return Map.of("contractId","CNT-"+UUID.randomUUID().toString().substring(0,8),"signatureHash","sima_sha256_"+UUID.randomUUID().toString().replace("-",""),"signedByUser",true,"signedByKampus",true,"provider","Mock SİMA İmza","demo",true);}
 @PostMapping("/lenders/applications") Object offers(@RequestHeader("X-Api-Key")String k,@RequestBody Map<String,Object>b){authorize(k);long a=n(b,"amountMinor");int term=(int)n(b,"termMonths");boolean overdue=Boolean.TRUE.equals(b.get("hasOverdueDebt"));boolean bokt=!overdue&&n(b,"activeMonths")>=2&&a<=150000,bank=!overdue&&n(b,"activeMonths")>=6&&n(b,"averageIncomeMinor")>=30000;return Map.of("offers",List.of(offer("Mock BOKT A",bokt,a,term,28),offer("Mock Bank B",bank,a,term,16)));}
 Map<String,Object> offer(String name,boolean approved,long a,int term,int apr){double r=Math.pow(1+apr/100.0,1.0/12)-1;long monthly=(long)Math.ceil(a*r/(1-Math.pow(1+r,-term)));return Map.of("partner",name,"approved",approved,"aprPercent",apr,"monthlyPaymentMinor",monthly,"totalPayableMinor",monthly*term,"reason",approved?"":"Demo gəlir və aktivlik meyarları ödənmir");}
 @PostMapping({"/bank/topup","/lenders/disburse"}) Object settlement(@RequestHeader("X-Api-Key")String k,@RequestBody Map<String,Object>b){authorize(k);return Map.of("status","COMPLETED","reference",UUID.randomUUID().toString(),"demo",true);}
 @GetMapping(value="/sim/admin",produces=MediaType.TEXT_HTML_VALUE) ResponseEntity<String> simAdmin(){return CardNetwork.html("<span class=\"badge\">KAMPUS-SIM · SANDBOX</span><h1>Xarici Sistem Simulyatoru.</h1><p>Bütün xarici partnyorlar lokal olaraq bu servisdə təqlid olunur:</p><section><strong>Aktiv Mock Servislər</strong><p>✓ Dövlət Reyestri və Üz Tutuşdurma (/registry/lookup)<br>✓ Demo Canlılıq Tapşırıqları (kamera analizi yoxdur) (/liveness/challenge)<br>✓ SİMA İmza Müqavilə Təsdiqi (/sima/sign)<br>✓ Kart Şəbəkəsi · OCT və BIN Tokenizasiya (/card-form/{id})<br>✓ Virtual Kart Emissiyası və Reveal (/cards/reveal)<br>✓ AniPay / AÖS və Partnyor Bank (/anipay/transfers)<br>✓ Mock VASP · USDT, BTC və AML Skrininq (/vasp/*)<br>✓ Mock BOKT A və Mock Bank B (/lenders/*)</p></section>");}
}
