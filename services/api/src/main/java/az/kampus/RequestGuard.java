package az.kampus;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded per-peer auth rate limit. The proxy's shared budget is intentional. */
@Component
public class RequestGuard extends OncePerRequestFilter {
 record Window(long minute,int count){}
 final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
  res.setHeader("X-Content-Type-Options","nosniff");
  res.setHeader("Cache-Control","no-store");
  String uri=req.getRequestURI();
  boolean selfie=Set.of("/api/v1/auth/register","/api/v1/auth/sca/kyc").contains(uri)||uri.matches("/api/v1/open-banking/consents/[0-9a-f-]{36}/sign");
  long limit=selfie?524288:131072;
  if(req.getContentLengthLong()>limit){reject(res,413,"REQUEST_TOO_LARGE");return;}
  if(req.getRequestURI().startsWith("/api/v1/auth/")&&!req.getRequestURI().equals("/api/v1/auth/sca")&&!req.getRequestURI().equals("/api/v1/auth/sca/kyc")){
   long minute=System.currentTimeMillis()/60000;
   if(windows.size()>10000)windows.entrySet().removeIf(e->e.getValue().minute()<minute);
   if(windows.size()>=10000&&!windows.containsKey(req.getRemoteAddr())){reject(res,429,"RATE_LIMIT");return;}
   var w=windows.compute(req.getRemoteAddr(),(key,old)->old==null||old.minute()!=minute?new Window(minute,1):new Window(minute,old.count()+1));
   if(w.count()>120){res.setHeader("Retry-After","60");reject(res,429,"RATE_LIMIT");return;}
  }
  chain.doFilter(req,res);
 }
 void reject(HttpServletResponse res,int status,String code)throws IOException{res.setStatus(status);res.setContentType("application/problem+json");res.getWriter().write("{\"code\":\""+code+"\",\"detail\":\"Sorğu limiti aşılıb. Bir qədər sonra yenidən sınayın.\"}");}
}
