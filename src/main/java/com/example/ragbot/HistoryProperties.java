package com.example.ragbot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.history")
public record HistoryProperties(
    @DefaultValue("30") int archiveAfterDays,
    @DefaultValue("365") int retentionDays,
    @DefaultValue("true") boolean deleteExpired,
    @DefaultValue("524288000") long userQuotaBytes,
    @DefaultValue("8192") int toolInlineChars,
    @DefaultValue("50") int pageSize,
    @DefaultValue("3600") int maintenanceIntervalSeconds
) {
  public HistoryProperties {
    if (archiveAfterDays < 1 || retentionDays <= archiveAfterDays || userQuotaBytes < 1024
        || toolInlineChars < 256 || pageSize < 1 || pageSize > 100 || maintenanceIntervalSeconds < 10) {
      throw new IllegalArgumentException("Invalid agent history limits");
    }
  }

  static HistoryProperties defaults() {
    return new HistoryProperties(30, 365, true, 500L * 1024 * 1024, 8192, 50, 3600);
  }
}
