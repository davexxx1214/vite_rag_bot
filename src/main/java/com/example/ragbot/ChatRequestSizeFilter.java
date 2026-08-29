package com.example.ragbot;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ChatRequestSizeFilter extends OncePerRequestFilter {
  static final long MAX_REQUEST_BYTES = 20L * 1_024L;
  private final ObjectMapper objectMapper;

  public ChatRequestSizeFilter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    if (request.getRequestURI().equals("/api/chat")
        && request.getContentLengthLong() > MAX_REQUEST_BYTES) {
      response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setHeader("Cache-Control", "no-store");
      objectMapper.writeValue(
          response.getOutputStream(),
          Map.of("error", "Request body must be 20 KB or smaller")
      );
      return;
    }
    filterChain.doFilter(request, response);
  }
}
