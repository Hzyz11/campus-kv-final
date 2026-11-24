package edu.baodex.campus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.time.*;

@SpringBootApplication
public class Application {
 public static void main(String[] args) { SpringApplication.run(Application.class, args); }
 @Bean Clock clock() { return new AdjustableClock(); }
}
