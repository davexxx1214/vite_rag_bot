package com.example.ssbotcli;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

public record Config(String apiKey, String chatModel, URI openaiBaseUrl,
                     String reasoningEffort, int timeoutSeconds, int maxRounds, int maxToolCalls) {
  public static Config load(Path file, Map<String, String> env) throws IOException {
    Properties p = new Properties();
    if (Files.exists(file)) try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
    URI url = URI.create(value(p, env, "OPENAI_BASE_URL", "openai_base_url", "https://api.openai.com/v1"));
    if (url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
        || !(url.getScheme().equals("https") || (url.getScheme().equals("http")
        && (url.getHost().equals("localhost") || url.getHost().equals("127.0.0.1")))))
      throw new IllegalArgumentException("API URL requires HTTPS (HTTP allowed only on localhost).");
    return new Config(value(p, env, "OPENAI_API_KEY", "api_key", ""),
        value(p, env, "OPENAI_MODEL", "chat_model", "gpt-5-mini"), url,
        p.getProperty("reasoning_effort", "low"), number(p, "timeout_seconds", 240, 1, 3600),
        number(p, "max_rounds", 8, 2, 32), number(p, "max_tool_calls", 12, 0, 64));
  }

  private static String value(Properties p, Map<String, String> env, String variable, String key, String fallback) {
    return env.getOrDefault(variable, p.getProperty(key, fallback)).trim();
  }

  private static int number(Properties p, String key, int fallback, int min, int max) {
    int n = Integer.parseInt(p.getProperty(key, Integer.toString(fallback)));
    if (n < min || n > max) throw new IllegalArgumentException("Invalid " + key);
    return n;
  }
}
