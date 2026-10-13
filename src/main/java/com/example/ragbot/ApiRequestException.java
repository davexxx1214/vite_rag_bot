package com.example.ragbot;

import org.springframework.http.HttpStatus;

final class ApiRequestException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final HttpStatus status;
  @SuppressWarnings("serial")
  private final java.util.Map<String, Object> details;

  ApiRequestException(HttpStatus status, String message) {
    this(status, message, java.util.Map.of());
  }

  ApiRequestException(HttpStatus status, String message, java.util.Map<String, Object> details) {
    super(message);
    this.status = status;
    this.details = java.util.Map.copyOf(details);
  }

  java.util.Map<String, Object> body() {
    var body = new java.util.LinkedHashMap<String, Object>(details);
    body.put("error", getMessage());
    body.put("status", status.value());
    return body;
  }

  HttpStatus status() {
    return status;
  }
}
