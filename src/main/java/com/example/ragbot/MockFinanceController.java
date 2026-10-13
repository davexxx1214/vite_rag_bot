package com.example.ragbot;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mock")
@ConditionalOnProperty(name = "rag.build-index", havingValue = "false", matchIfMissing = true)
public class MockFinanceController {
  private final MockFinanceService finance;

  public MockFinanceController(MockFinanceService finance) {
    this.finance = finance;
  }

  @GetMapping("/users")
  public List<MockFinanceService.User> users() {
    return finance.users();
  }

  @GetMapping("/portfolios")
  public List<MockFinanceService.Portfolio> portfolios(
      @RequestParam(required = false) String id, @RequestParam(required = false) String username
  ) {
    return finance.portfolios(id, username);
  }

  @GetMapping("/portfolios/{id}")
  public MockFinanceService.Portfolio portfolio(
      @PathVariable String id, @RequestParam(required = false) String username
  ) {
    return finance.portfolio(id, username);
  }

  @PatchMapping("/portfolios/{id}")
  public java.util.Map<String, Object> updatePortfolio(@PathVariable String id,
      @RequestParam String username, @RequestHeader("Idempotency-Key") String operationId,
      @RequestBody tools.jackson.databind.JsonNode body) {
    if (!body.isObject() || body.size() != 2 || !body.has("name") || !body.has("expectedVersion")
        || !body.path("name").isString() || !body.path("expectedVersion").isIntegralNumber()
        || !body.path("expectedVersion").canConvertToLong())
      throw new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST, "Only name and integer expectedVersion are accepted",
          java.util.Map.of("code", "VALIDATION_ERROR", "field", "request", "retryable", false));
    var edit = new MockFinanceService.PortfolioEdit(body.path("name").asString(), body.path("expectedVersion").asLong());
    return finance.updatePortfolio(id, username, operationId, edit);
  }

  @GetMapping("/operations/{id}")
  public java.util.Map<String, Object> operation(@PathVariable String id, @RequestParam String username) {
    return finance.operation(id, username);
  }

  @GetMapping("/issuers")
  public List<MockFinanceService.Issuer> issuers(
      @RequestParam(required = false) String id, @RequestParam(required = false) String username
  ) {
    return finance.issuers(id, username);
  }

  @GetMapping("/issuers/{id}")
  public MockFinanceService.Issuer issuer(
      @PathVariable String id, @RequestParam(required = false) String username
  ) {
    return finance.issuer(id, username);
  }

  @GetMapping("/accounts")
  public List<MockFinanceService.Account> accounts(
      @RequestParam(required = false) String id, @RequestParam(required = false) String username
  ) {
    return finance.accounts(id, username);
  }

  @GetMapping("/accounts/{id}")
  public MockFinanceService.Account account(
      @PathVariable String id, @RequestParam(required = false) String username
  ) {
    return finance.account(id, username);
  }

  @GetMapping("/portfolios/{id}/positions")
  public List<MockFinanceService.Position> positions(
      @PathVariable String id, @RequestParam(required = false) String username
  ) {
    return finance.positions(id, username);
  }

  @GetMapping("/portfolios/{id}/transactions")
  public List<MockFinanceService.Transaction> transactions(
      @PathVariable String id, @RequestParam(required = false) String username
  ) {
    return finance.transactions(id, username);
  }
}
