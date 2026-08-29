package com.example.ragbot;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    Path projectRoot = Path.of("").toAbsolutePath().normalize();
    boolean buildIndex = false;
    Integer portOverride = null;

    for (String argument : args) {
      if (argument.equals("--build-index")) {
        buildIndex = true;
      } else if (argument.startsWith("--port=")) {
        portOverride = Integer.parseInt(argument.substring("--port=".length()));
      } else {
        throw new IllegalArgumentException("Unknown argument: " + argument);
      }
    }

    AppConfig config = AppConfig.load(projectRoot, portOverride);
    List<HelpDoc> docs = HelpDocs.load(projectRoot);
    OpenAiClient openAi = new OpenAiClient(config);

    if (buildIndex) {
      IndexBuilder.build(projectRoot, config, docs, openAi);
      return;
    }

    RagIndex ragIndex = RagIndex.load(projectRoot);
    HelpServer helpServer = new HelpServer(projectRoot, config, docs, ragIndex, openAi);
    CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      helpServer.close();
      stopped.countDown();
    }, "ragbot-shutdown"));
    helpServer.start();
    stopped.await();
  }
}
