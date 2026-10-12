package com.example.ragbot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Reception limits are independent of the much smaller model tool-output budget. */
@ConfigurationProperties("agent.result-sets")
public record ResultSetProperties(
    @DefaultValue("8388608") int maxResponseBytes,
    @DefaultValue("10000") int maxRecords,
    @DefaultValue("1800") int ttlSeconds
) {
  public ResultSetProperties {
    if (maxResponseBytes < 1024 || maxResponseBytes > 64 * 1024 * 1024
        || maxRecords < 1 || maxRecords > 100_000 || ttlSeconds < 1)
      throw new IllegalArgumentException("Invalid result-set limits");
  }

  static ResultSetProperties defaults() { return new ResultSetProperties(8 * 1024 * 1024, 10_000, 1800); }
}
