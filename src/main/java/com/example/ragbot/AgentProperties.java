package com.example.ragbot;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent")
public record AgentProperties(
    Path memoryDirectory, URI financeBaseUrl, int inputBudgetTokens, int compactAtTokens,
    int compactTargetTokens, int keepRecentTokens, int memoryBudgetTokens, int maxOutputTokens,
    int contextWindowTokens, int maxRounds, int maxToolCallsPerRound, int toolResultTokens,
    int requestTimeoutSeconds, int memoryExtractionInterval, String reasoningEffort
) {
  public AgentProperties {
    if (memoryDirectory == null) throw new IllegalArgumentException("agent.memory-directory is required");
    if (inputBudgetTokens < 2_000 || compactAtTokens >= inputBudgetTokens
        || compactTargetTokens >= compactAtTokens || compactTargetTokens < 500
        || keepRecentTokens < 100 || keepRecentTokens >= compactTargetTokens
        || memoryBudgetTokens < 100 || maxOutputTokens < 100 || contextWindowTokens < 0
        || maxRounds < 2 || maxRounds > 20 || maxToolCallsPerRound < 1
        || toolResultTokens < 100 || requestTimeoutSeconds < 5 || memoryExtractionInterval < 1) {
      throw new IllegalArgumentException("Invalid agent context, memory or run limits");
    }
    reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? "low" : reasoningEffort;
  }

  public int inputLimit(String model) {
    int window = contextWindowTokens > 0 ? contextWindowTokens
        : switch (model) {
          case "gpt-5-mini", "gpt-5-mini-2025-08-07" -> 400_000;
          case "gpt-6-luna" -> 1_050_000;
          default -> 128_000;
        };
    int limit = Math.min(inputBudgetTokens, window - maxOutputTokens - 4_000);
    if (limit < 2_000) throw new IllegalArgumentException("Model window leaves insufficient input space");
    return limit;
  }
}
