package com.example.ragbot;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RagConfiguration {
  @Bean
  @ConditionalOnProperty(name = "rag.build-index", havingValue = "false", matchIfMissing = true)
  RagIndex ragIndex(RagProperties properties, ObjectMapper objectMapper) throws IOException {
    return RagIndex.load(properties.indexPath(), objectMapper);
  }
}
