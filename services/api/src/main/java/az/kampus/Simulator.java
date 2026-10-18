package az.kampus;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import java.util.Map;
@Component public class Simulator {
 static final ThreadLocal<String> operation=new ThreadLocal<>();
 final RestClient client;
 Simulator(@Value("${kampus.sim-url}")String url,@Value("${kampus.sim-key}")String key){var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());factory.setReadTimeout(java.time.Duration.ofSeconds(5));client=RestClient.builder().requestFactory(factory).baseUrl(url).defaultHeader("X-Api-Key",key).build();}
 @SuppressWarnings("unchecked") Map<String,Object> call(String path,Map<String,Object> data){try{var request=new java.util.LinkedHashMap<>(data);if(operation.get()!=null)request.put("operationId",operation.get()+":"+path);return client.post().uri(path).body(request).retrieve().body(Map.class);}catch(Exception e){throw new Problem(503,"SIMULATOR_UNAVAILABLE","Demo partnyor cavab vermir və ya əməliyyatı rədd etdi.");}}
}
