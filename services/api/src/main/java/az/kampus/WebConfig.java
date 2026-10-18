package az.kampus;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
@Configuration public class WebConfig implements WebMvcConfigurer {
 @org.springframework.beans.factory.annotation.Value("${kampus.cors-origins}") String origins;
 public void addCorsMappings(CorsRegistry r){r.addMapping("/api/**").allowedOriginPatterns(origins.split(",")).allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS").allowedHeaders("*");}
}
