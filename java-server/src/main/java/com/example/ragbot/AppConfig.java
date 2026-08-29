package com.example.ragbot;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

record AppConfig(
    String apiKey,
    String chatModel,
    String embeddingModel,
    int port,
    URI openAiBaseUrl
) {
  static AppConfig load(Path projectRoot, Integer portOverride) throws IOException {
    Map<String, String> values = new LinkedHashMap<>();
    Path configPath = projectRoot.resolve("config.yaml");
    if (Files.isRegularFile(configPath)) {
      for (String line : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
        int separator = trimmed.indexOf(':');
        if (separator <= 0) continue;
        String key = trimmed.substring(0, separator).trim();
        String value = unquote(trimmed.substring(separator + 1).trim());
        values.put(key, value);
      }
    }

    String apiKey = firstNonBlank(System.getenv("OPENAI_API_KEY"), values.get("api_key"));
    if (apiKey == null) {
      throw new IllegalStateException(
          "Missing OpenAI API key. Set api_key in config.yaml or OPENAI_API_KEY."
      );
    }

    int configuredPort = parsePort(
        firstNonBlank(System.getenv("PORT"), values.get("port")),
        3000
    );
    int port = portOverride == null ? configuredPort : portOverride;
    String baseUrl = firstNonBlank(
        System.getenv("OPENAI_BASE_URL"),
        values.get("openai_base_url"),
        "https://api.openai.com/v1"
    );

    return new AppConfig(
        apiKey,
        firstNonBlank(values.get("chat_model"), "gpt-5-mini"),
        firstNonBlank(values.get("embedding_model"), "text-embedding-3-small"),
        port,
        URI.create(stripTrailingSlash(baseUrl))
    );
  }

  private static int parsePort(String value, int fallback) {
    if (value == null) return fallback;
    try {
      int port = Integer.parseInt(value);
      return port > 0 && port <= 65_535 ? port : fallback;
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return value.trim();
    }
    return null;
  }

  private static String unquote(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }

  private static String stripTrailingSlash(String value) {
    String result = value.trim();
    while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
    return result;
  }
}
