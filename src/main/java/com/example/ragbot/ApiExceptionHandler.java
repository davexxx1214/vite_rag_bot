package com.example.ragbot;

import java.util.Map;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ApiRequestException.class)
  public ResponseEntity<Map<String, Object>> requestError(ApiRequestException error) {
    return ResponseEntity.status(error.status()).body(error.body());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> malformedJson() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", "Request body must be valid JSON"));
  }

  @ExceptionHandler({IOException.class, InterruptedException.class})
  public ResponseEntity<Map<String, String>> agentStorageOrModelError(Exception error) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", HelpController.failureMessage(error)));
  }
}
