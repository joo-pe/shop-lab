package com.carroti.citizen;
import org.springframework.boot.SpringApplication;
/** Optional local UI demo only. H2 data is lost when this process exits. */
public class LocalDemoApplication {
 public static void main(String[] args) {
  System.setProperty("spring.profiles.active","test");
  System.setProperty("spring.web.resources.static-locations","file:./web/");
  SpringApplication.run(CitizenApplication.class,args);
 }
}
