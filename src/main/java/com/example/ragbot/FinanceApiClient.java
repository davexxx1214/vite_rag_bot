package com.example.ragbot;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "rag.build-index", havingValue = "false", matchIfMissing = true)
public final class FinanceApiClient {
  private final AgentProperties properties;
  private final ObjectMapper mapper;
  private final ResultSetProperties resultSets;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private volatile URI localBaseUrl;

  public FinanceApiClient(AgentProperties properties, ObjectMapper mapper) {
    this(properties, mapper, ResultSetProperties.defaults());
  }

  @Autowired
  public FinanceApiClient(AgentProperties properties, ObjectMapper mapper, ResultSetProperties resultSets) {
    this.properties = properties;
    this.mapper = mapper;
    this.resultSets = resultSets;
  }

  @EventListener
  public void serverStarted(WebServerInitializedEvent event) {
    localBaseUrl = URI.create("http://127.0.0.1:" + event.getWebServer().getPort() + "/api/mock");
  }

  public URI uri(String path, MockFinanceService.User user) {
    return uri(path, Map.of(), user);
  }

  public URI uri(String path, Map<String, String> query, MockFinanceService.User user) {
    URI base = properties.financeBaseUrl() == null ? localBaseUrl : properties.financeBaseUrl();
    if (base == null) throw new IllegalStateException("Finance API is not ready");
    String url = base.toString().replaceAll("/+$", "");
    StringBuilder target = new StringBuilder(url).append(path).append("?username=")
        .append(URLEncoder.encode(user.username(), StandardCharsets.UTF_8));
    query.forEach((key, value) -> {
      if (List.of("username", "userid", "user_id").contains(key.toLowerCase(java.util.Locale.ROOT)))
        throw new IllegalArgumentException("Identity query parameters are server-controlled");
      target.append('&').append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
          .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    });
    return URI.create(target.toString());
  }

  public String sourceUrl(String path, MockFinanceService.User user) {
    return sourceUrl(path, Map.of(), user);
  }

  public String sourceUrl(String path, Map<String, String> query, MockFinanceService.User user) {
    URI target = uri(path, query, user);
    return properties.financeBaseUrl() == null
        ? target.getRawPath() + "?" + target.getRawQuery() : target.toString();
  }

  public JsonNode get(String path, MockFinanceService.User user, RunControl control)
      throws IOException, InterruptedException {
    return get(path, Map.of(), user, control);
  }

  public JsonNode get(String path, Map<String, String> query, MockFinanceService.User user, RunControl control)
      throws IOException, InterruptedException {
    return getBounded(path, query, user, control, 131_072);
  }

  public JsonNode portfolios(MockFinanceService.User user, RunControl control)
      throws IOException, InterruptedException {
    return getBounded("/portfolios", Map.of(), user, control, resultSets.maxResponseBytes());
  }

  private JsonNode getBounded(String path, Map<String, String> query, MockFinanceService.User user,
      RunControl control, int maxBytes) throws IOException, InterruptedException {
    control.check();
    HttpRequest request = HttpRequest.newBuilder(uri(path, query, user))
        .timeout(Duration.ofSeconds(Math.min(10, Math.max(1, control.timeout().toSeconds()))))
        .header("Accept", "application/json").GET().build();
    return receive(request, control, maxBytes);
  }

  public JsonNode updatePortfolio(String id, String name, long version, String operationId,
      MockFinanceService.User user, RunControl control) throws IOException, InterruptedException {
    control.check();
    HttpRequest request = HttpRequest.newBuilder(uri("/portfolios/" + segment(id), user))
        .timeout(Duration.ofSeconds(Math.min(10, Math.max(1, control.timeout().toSeconds()))))
        .header("Accept", "application/json").header("Content-Type", "application/json")
        .header("Idempotency-Key", operationId)
        .method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
            Map.of("name", name, "expectedVersion", version)))).build();
    return receive(request, control, 131_072);
  }

  private JsonNode receive(HttpRequest request, RunControl control, int maxBytes)
      throws IOException, InterruptedException {
    HttpResponse<InputStream> response = control.send(http, request);
    try (InputStream body = response.body()) {
      try {
        byte[] bytes = body.readNBytes(maxBytes + 1);
        control.check();
        if (bytes.length > maxBytes) throw new IOException("Finance API result is too large; narrow the query");
        JsonNode result;
        try {
          result = mapper.readerFor(JsonNode.class)
              .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(bytes);
        } catch (tools.jackson.core.JacksonException error) {
          throw new IOException("Finance API returned incomplete or invalid JSON", error);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          Map<String, Object> details = new LinkedHashMap<>();
          for (String field : List.of("code", "field", "retryable", "details"))
            if (result.hasNonNull(field)) details.put(field, mapper.convertValue(result.path(field), Object.class));
          throw new ApiRequestException(HttpStatus.valueOf(response.statusCode()),
              result.path("error").asString("Finance request failed"), details);
        }
        return result;
      } finally {
        control.detach();
      }
    }
  }

  static String segment(String id) {
    if (id == null || !id.matches("[A-Za-z0-9-]{1,80}")) {
      throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid business resource ID");
    }
    return id;
  }

  public JsonNode compare(String leftId, String rightId, MockFinanceService.User user, RunControl control)
      throws IOException, InterruptedException {
    JsonNode left = get("/portfolios/" + segment(leftId), user, control);
    JsonNode right = get("/portfolios/" + segment(rightId), user, control);
    JsonNode leftPositions = get("/portfolios/" + leftId + "/positions", user, control);
    JsonNode rightPositions = get("/portfolios/" + rightId + "/positions", user, control);
    boolean sameCurrency = left.path("currency").asString().equals(right.path("currency").asString());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("left", left);
    result.put("right", right);
    result.put("leftAllocation", allocation(left, leftPositions));
    result.put("rightAllocation", allocation(right, rightPositions));
    result.put("sameCurrency", sameCurrency);
    result.put("differenceDirection", "left minus right");
    Map<String, BigDecimal> differences = new LinkedHashMap<>();
    if (sameCurrency) {
      for (String field : List.of("cashBalance", "marketValue", "totalValue", "unrealizedPnl")) {
        differences.put(field, decimal(left, field).subtract(decimal(right, field)));
      }
    }
    result.put("monetaryDifferences", differences);
    result.put("note", sameCurrency ? "Values use each portfolio's asOf snapshot."
        : "Different currencies: monetary differences and FX conversion are unavailable.");
    return mapper.valueToTree(result);
  }

  private static Map<String, Object> allocation(JsonNode portfolio, JsonNode positions) {
    List<Map<String, Object>> issuers = new ArrayList<>();
    Map<String, BigDecimal> values = new LinkedHashMap<>();
    for (JsonNode position : positions) {
      values.merge(position.path("issuerId").asString(), decimal(position, "marketValue"), BigDecimal::add);
    }
    values.forEach((issuerId, value) -> {
      Map<String, Object> issuer = new LinkedHashMap<>();
      issuer.put("issuerId", issuerId);
      issuer.put("marketValue", value);
      issuer.put("weightOfInvestedAssetsPct", percentage(value, decimal(portfolio, "marketValue")));
      issuers.add(issuer);
    });
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("cashPctOfTotal", percentage(decimal(portfolio, "cashBalance"), decimal(portfolio, "totalValue")));
    result.put("issuers", issuers);
    return result;
  }

  private static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
    return denominator.signum() == 0 ? null
        : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal decimal(JsonNode node, String field) {
    return new BigDecimal(node.path(field).toString());
  }
}
