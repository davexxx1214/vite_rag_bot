package com.example.ragbot;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "rag.build-index", havingValue = "true")
public class IndexBuildRunner implements ApplicationRunner {
  private final IndexBuilder indexBuilder;
  private final ConfigurableApplicationContext context;

  public IndexBuildRunner(IndexBuilder indexBuilder, ConfigurableApplicationContext context) {
    this.indexBuilder = indexBuilder;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    indexBuilder.build();
    int exitCode = SpringApplication.exit(context);
    System.exit(exitCode);
  }
}
