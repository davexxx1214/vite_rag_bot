package com.example.ragbot;

import org.springframework.http.HttpStatus;

final class ApiRequestException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final HttpStatus status;

  ApiRequestException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  HttpStatus status() {
    return status;
  }
}
