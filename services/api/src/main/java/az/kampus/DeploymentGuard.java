package az.kampus;

import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;

/** Refuse the hosted profile with local credentials or a file database. */
@Component
public class DeploymentGuard {
 DeploymentGuard(Environment env){
  if(!env.getProperty("kampus.deployed",Boolean.class,false))return;
  for(String name:new String[]{"kampus.demo-pin","kampus.admin-pin"}){
   String pin=env.getProperty(name,"");
   if(!pin.matches("[0-9]{6}")||pin.equals("123456"))throw new IllegalStateException("Hosted demo needs a non-default six digit PIN: "+name);
  }
  String key=env.getProperty("kampus.sim-key","");
  if(key.length()<32||key.equals("kampus-local-simulator-key"))throw new IllegalStateException("Hosted simulator key must be random and at least 32 characters");
  String media=env.getProperty("kampus.kyc-media-key","");
  if(media.length()<32||media.equals(KycMedia.LOCAL_KEY))throw new IllegalStateException("Hosted KYC media key must be random and at least 32 characters");
  if(!env.getProperty("spring.datasource.url","").startsWith("jdbc:postgresql:"))throw new IllegalStateException("Hosted demo requires PostgreSQL");
 }
}
