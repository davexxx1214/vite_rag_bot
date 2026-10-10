package com.example.ragbot;

import java.io.IOException;

/** Safe diagnostics derived from status/code/parameter, never the provider's raw message. */
public final class ModelApiException extends IOException {
  private static final long serialVersionUID = 1L;
  private final int status;
  private final String code;
  private final String parameter;

  ModelApiException(int status, String code, String parameter) {
    super("Model API HTTP " + status + " (" + safeField(code) + ")"
        + (safeField(parameter).isEmpty() ? "" : ", parameter=" + safeField(parameter)));
    this.status = status;
    this.code = safeField(code);
    this.parameter = safeField(parameter);
  }

  public String publicMessage() {
    String detail = " (HTTP " + status + (code.isEmpty() ? "" : ", " + code) + ").";
    if (status == 401) return "The model API rejected the API key. Check api_key and openai_base_url in config.yaml, then restart the server." + detail;
    if (status == 403) return "The model API denied access. Check your project's model permissions and API access." + detail;
    if (code.equals("model_not_found") || status == 404) {
      return "The configured model or API endpoint is unavailable. Check chat_model, openai_base_url and model access." + detail;
    }
    if (code.equals("insufficient_quota")) return "The model API quota is exhausted. Check API billing and project limits." + detail;
    if (status == 429) return "The model API rate limit was reached. Please retry shortly." + detail;
    if (status >= 500) return "The model API is temporarily unavailable. Please retry shortly." + detail;
    return "The model API rejected the request" + (parameter.isEmpty() ? "" : "; check parameter " + parameter) + detail;
  }

  private static String safeField(String value) {
    return value != null && value.matches("[a-zA-Z0-9_.\\[\\]-]{1,100}") ? value : "";
  }
}
