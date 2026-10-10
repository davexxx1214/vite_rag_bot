package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;

class ModelApiExceptionTest {
  @Test
  void distinguishesActionableApiFailures() {
    assertThat(new ModelApiException(401, "invalid_api_key", "").publicMessage()).contains("API key", "restart");
    assertThat(new ModelApiException(403, "permission_denied", "").publicMessage()).contains("permissions");
    assertThat(new ModelApiException(404, "model_not_found", "model").publicMessage()).contains("chat_model");
    assertThat(new ModelApiException(429, "insufficient_quota", "").publicMessage()).contains("billing");
    assertThat(new ModelApiException(429, "rate_limit_exceeded", "").publicMessage()).contains("rate limit");
    assertThat(new ModelApiException(503, "", "").publicMessage()).contains("temporarily unavailable");
    assertThat(new ModelApiException(400, "invalid_request_error", "tools[0].parameters").publicMessage()).contains("tools[0].parameters");
  }

  @Test
  void excludesUnsafeFieldsAndDistinguishesNetworkTimeoutsAndCancellation() {
    var error = new ModelApiException(400, "Bearer secret/value", "prompt contains private data");
    assertThat(error.getMessage() + error.publicMessage()).doesNotContain("secret", "private");
    assertThat(HelpController.failureMessage(new HttpTimeoutException("Remote private details"))).contains("timed out").doesNotContain("private");
    assertThat(HelpController.failureMessage(new IOException("Agent request cancelled"))).isEqualTo("The request was cancelled.");
    assertThat(HelpController.failureMessage(new IOException("secret storage details"))).doesNotContain("secret");
  }
}
