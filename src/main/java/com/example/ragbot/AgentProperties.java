package com.example.ragbot;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("agent")
public record AgentProperties(
    Path memoryDirectory, URI financeBaseUrl, int inputBudgetTokens, int compactAtTokens,
    int compactTargetTokens, int keepRecentTokens, int memoryBudgetTokens, int maxOutputTokens,
    int contextWindowTokens, int maxRounds, int maxToolCallsPerRound, int toolResultTokens,
    int requestTimeoutSeconds, int memoryExtractionInterval, String reasoningEffort,
    @DefaultValue("12") int maxToolCalls, @DefaultValue("40") int finalAnswerReserveSeconds
) {
  @ConstructorBinding
  public AgentProperties {
    if (memoryDirectory == null) throw new IllegalArgumentException("agent.memory-directory is required");
    if (inputBudgetTokens < 2_000 || compactAtTokens >= inputBudgetTokens
        || compactTargetTokens >= compactAtTokens || compactTargetTokens < 500
        || keepRecentTokens < 100 || keepRecentTokens >= compactTargetTokens
        || memoryBudgetTokens < 100 || maxOutputTokens < 100 || contextWindowTokens < 0
        || maxRounds < 2 || maxRounds > 64 || maxToolCallsPerRound < 1
        || maxToolCalls < 1 || maxToolCalls > 100 || finalAnswerReserveSeconds < 1
        || finalAnswerReserveSeconds >= requestTimeoutSeconds
        || toolResultTokens < 100 || requestTimeoutSeconds < 5 || memoryExtractionInterval < 1) {
      throw new IllegalArgumentException("Invalid agent context, memory or run limits");
    }
    reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? "low" : reasoningEffort;
  }

  public AgentProperties(Path memoryDirectory, URI financeBaseUrl, int inputBudgetTokens, int compactAtTokens,
      int compactTargetTokens, int keepRecentTokens, int memoryBudgetTokens, int maxOutputTokens,
      int contextWindowTokens, int maxRounds, int maxToolCallsPerRound, int toolResultTokens,
      int requestTimeoutSeconds, int memoryExtractionInterval, String reasoningEffort) {
    this(memoryDirectory, financeBaseUrl, inputBudgetTokens, compactAtTokens, compactTargetTokens,
        keepRecentTokens, memoryBudgetTokens, maxOutputTokens, contextWindowTokens, maxRounds,
        maxToolCallsPerRound, toolResultTokens, requestTimeoutSeconds, memoryExtractionInterval,
        reasoningEffort, 12, Math.min(40, requestTimeoutSeconds / 3));
  }

  public int compactThreshold(String model) {
    return Math.min(compactAtTokens, inputLimit(model) * 3 / 4);
  }

  public int compactTarget(String model) {
    return Math.min(compactTargetTokens, compactThreshold(model) * 2 / 3);
  }

  public int recentLimit(String model) {
    return Math.min(keepRecentTokens, compactTarget(model) / 2);
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
