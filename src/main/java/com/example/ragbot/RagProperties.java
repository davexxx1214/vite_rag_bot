package com.example.ragbot;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rag")
public record RagProperties(
    String apiKey,
    String chatModel,
    String embeddingModel,
    URI openaiBaseUrl,
    Path docsDirectory,
    Path indexPath,
    boolean buildIndex
) {
  public RagProperties {
    apiKey = apiKey == null ? "" : apiKey.trim();
    chatModel = requireText(chatModel, "rag.chat-model");
    embeddingModel = requireText(embeddingModel, "rag.embedding-model");
    if (openaiBaseUrl == null) throw new IllegalArgumentException("rag.openai-base-url is required");
    if (docsDirectory == null) throw new IllegalArgumentException("rag.docs-directory is required");
    if (indexPath == null) throw new IllegalArgumentException("rag.index-path is required");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }
}
