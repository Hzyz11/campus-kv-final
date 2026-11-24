package edu.baodex.campus;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.*;
import java.io.IOException;
import java.sql.Timestamp;

@Configuration
public class JsonConfig {
 @Bean SimpleModule localTimestampModule() {
  SimpleModule module=new SimpleModule();
  module.addSerializer(Timestamp.class,new JsonSerializer<Timestamp>() {
   @Override public void serialize(Timestamp value,JsonGenerator gen,SerializerProvider provider) throws IOException {
    gen.writeString(value.toLocalDateTime().toString());
   }
  });
  return module;
 }
}
