package az.kampus;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import static az.kampus.Store.*;
@RestController @RequestMapping("/api/v1") public class ApiController {
 final Auth auth;final Engine e;final Store store;final KycMedia media;
 ApiController(Auth auth,Engine e,Store store,KycMedia media){this.auth=auth;this.e=e;this.store=store;this.media=media;}
 @GetMapping("/health") Object health(){return m("status","UP","service","kampus-api","demo",true);}
 @GetMapping("/health/ready") Object ready(){store.db.queryForObject("SELECT 1",Integer.class);return m("status","UP","database","UP","demo",true);}
 @PostMapping("/auth/login") Object login(@RequestBody Map<String,Object>b){return auth.login(b);}
 @PostMapping("/auth/identify") Object identify(@RequestBody Map<String,Object>b){return auth.identify(b);}
 @PostMapping("/auth/otp/verify") Object verifyOtp(@RequestBody Map<String,Object>b){return auth.verifyOtp(b);}
 @PostMapping("/auth/otp") Object otp(@RequestBody Map<String,Object>b){return auth.otp(b);}
 @PostMapping("/auth/identity/validate") Object validateIdentity(@RequestBody Map<String,Object>b){return auth.validateIdentity(b);}
 @PostMapping("/auth/register") Object register(@RequestBody Map<String,Object>b){return auth.register(b);}
 @PostMapping("/auth/sca/kyc") Object scaKyc(@RequestHeader("Authorization")String token,@RequestHeader("X-Device-Id")String device,@RequestBody Map<String,Object>b){return auth.kycConfirm(auth.user(token,device),b);}
 @PostMapping("/auth/sca") Object sca(@RequestHeader("Authorization")String token,@RequestHeader("X-Device-Id")String device,@RequestBody Map<String,Object>b){return auth.sca(auth.user(token,device),b);}
 @PostMapping("/auth/logout") Object logout(HttpServletRequest r){var u=auth.user(r.getHeader("Authorization"),r.getHeader("X-Device-Id"));store.db.update("DELETE FROM sessions WHERE token=?",r.getHeader("Authorization").substring(7));return m("loggedOut",true);}
 @GetMapping("/admin/kyc/{userId}/selfie") Object kycSelfie(HttpServletRequest r,@PathVariable String userId){var u=auth.user(r.getHeader("Authorization"),r.getHeader("X-Device-Id"));auth.role(u,"ADMIN","COMPLIANCE");return media.selfie(userId,u.id());}
 @GetMapping("/**") Object read(HttpServletRequest r,@RequestParam(required=false)String phone){var u=auth.user(r.getHeader("Authorization"),r.getHeader("X-Device-Id"));return e.read(u,r.getRequestURI().substring("/api/v1/".length()),phone);}
 @RequestMapping(value="/**",method={RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE}) Object write(HttpServletRequest r,@RequestBody(required=false)Map<String,Object>b){var u=auth.user(r.getHeader("Authorization"),r.getHeader("X-Device-Id"));return e.write(u,r.getRequestURI().substring("/api/v1/".length()),b==null?m():b,r.getHeader("Idempotency-Key"),r.getHeader("X-SCA-Proof"));}
}
