package com.example.ragbot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Deterministic, read-only fixtures. No market data or external API calls. */
@Service
public final class MockFinanceService {
  private static final String AS_OF = "2026-10-09T16:00:00Z";

  private final List<User> users = List.of(
      new User("user-001", "alice", "Alice Chen"),
      new User("user-002", "bob", "Bob Wong"),
      new User("user-003", "charlie", "Charlie Lee")
  );
  private final List<Issuer> issuers = List.of(
      new Issuer("iss-001", "Nova Technology (Demo)", "US", "Technology", "NOVA"),
      new Issuer("iss-002", "Harbor Bank (Demo)", "US", "Financial Services", "HBR"),
      new Issuer("iss-003", "Jade Energy (Demo)", "HK", "Energy", "JADE.HK"),
      new Issuer("iss-004", "Atlas Treasury (Demo)", "US", "Government", "ATLAS-2030")
  );
  private final List<Position> positions = List.of(
      position("pos-001", "pf-001", "iss-001", "NOVA", "EQUITY", "USD", "20", "100", "120"),
      position("pos-002", "pf-001", "iss-002", "HBR", "EQUITY", "USD", "10", "80", "75"),
      position("pos-003", "pf-002", "iss-003", "JADE.HK", "EQUITY", "HKD", "100", "50", "55"),
      position("pos-004", "pf-003", "iss-001", "NOVA", "EQUITY", "USD", "5", "110", "120"),
      position("pos-005", "pf-003", "iss-004", "ATLAS-2030", "BOND", "USD", "10", "100", "101")
  );
  private final List<Portfolio> portfolios = portfolioFixtures();
  private final List<Account> accounts = List.of(
      account("acc-001", "pf-001", "CASH"),
      account("acc-002", "pf-002", "CASH"),
      account("acc-003", "pf-003", "MARGIN"),
      account("acc-004", "pf-004", "CASH")
  );
  private final List<Transaction> transactions = List.of(
      transaction("txn-001", "pf-001", "iss-001", "NOVA", "BUY", "USD", "20", "100", "2026-10-01T14:30:00Z"),
      transaction("txn-002", "pf-001", "iss-002", "HBR", "BUY", "USD", "10", "80", "2026-10-02T14:30:00Z"),
      transaction("txn-003", "pf-002", "iss-003", "JADE.HK", "BUY", "HKD", "100", "50", "2026-10-05T02:00:00Z"),
      transaction("txn-004", "pf-003", "iss-001", "NOVA", "BUY", "USD", "6", "110", "2026-10-05T14:30:00Z"),
      transaction("txn-005", "pf-003", "iss-004", "ATLAS-2030", "BUY", "USD", "10", "100", "2026-10-06T14:30:00Z"),
      transaction("txn-006", "pf-003", "iss-001", "NOVA", "SELL", "USD", "1", "115", "2026-10-07T14:30:00Z")
  );

  public List<User> users() {
    return users;
  }

  /** Alice has 3,000 portfolios so her user-scoped endpoint exercises a full large response. */
  private List<Portfolio> portfolioFixtures() {
    var fixtures = new ArrayList<Portfolio>();
    fixtures.add(portfolio("pf-001", "alice", "US Growth", "USD", "1850"));
    fixtures.add(portfolio("pf-002", "alice", "Hong Kong Income", "HKD", "4500"));
    fixtures.add(portfolio("pf-003", "bob", "Balanced", "USD", "3390"));
    fixtures.add(portfolio("pf-004", "charlie", "New Portfolio", "USD", "10000"));
    for (int i = 5; i <= 3002; i++) {
      fixtures.add(portfolio("pf-" + String.format(Locale.ROOT, "%04d", i), "alice",
          "Demo Portfolio " + i, i % 2 == 0 ? "HKD" : "USD", Integer.toString(1000 + i * 10)));
    }
    return List.copyOf(fixtures);
  }

  public List<Portfolio> portfolios(String id, String username) {
    return filter(portfolios, id, username, Portfolio::id, Portfolio::username);
  }

  public Portfolio portfolio(String id, String username) {
    return requireOne(portfolios(id, username), "Portfolio");
  }

  public List<Issuer> issuers(String id, String username) {
    String normalizedId = normalize(id, "id");
    String normalizedUsername = normalizeUsername(username);
    List<String> portfolioIds = portfolios(null, normalizedUsername).stream()
        .map(Portfolio::id).toList();
    List<String> issuerIds = positions.stream()
        .filter(position -> portfolioIds.contains(position.portfolioId()))
        .map(Position::issuerId).toList();
    return issuers.stream()
        .filter(issuer -> normalizedId == null || issuer.id().equals(normalizedId))
        .filter(issuer -> normalizedUsername == null || issuerIds.contains(issuer.id()))
        .toList();
  }

  public Issuer issuer(String id, String username) {
    return requireOne(issuers(id, username), "Issuer");
  }

  public List<Account> accounts(String id, String username) {
    return filter(accounts, id, username, Account::id, Account::username);
  }

  public Account account(String id, String username) {
    return requireOne(accounts(id, username), "Account");
  }

  public List<Position> positions(String portfolioId, String username) {
    Portfolio selected = portfolio(portfolioId, username);
    return positions.stream().filter(position -> position.portfolioId().equals(selected.id())).toList();
  }

  public List<Transaction> transactions(String portfolioId, String username) {
    Portfolio selected = portfolio(portfolioId, username);
    return transactions.stream().filter(transaction -> transaction.portfolioId().equals(selected.id())).toList();
  }

  private static <T> List<T> filter(
      List<T> items, String id, String username,
      Function<T, String> idExtractor, Function<T, String> usernameExtractor
  ) {
    String normalizedId = normalize(id, "id");
    String normalizedUsername = normalizeUsername(username);
    return items.stream()
        .filter(item -> normalizedId == null || idExtractor.apply(item).equals(normalizedId))
        .filter(item -> normalizedUsername == null || usernameExtractor.apply(item).equals(normalizedUsername))
        .toList();
  }

  private static String normalizeUsername(String username) {
    String normalized = normalize(username, "username");
    return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
  }

  private static String normalize(String value, String name) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) {
      throw new ApiRequestException(HttpStatus.BAD_REQUEST, name + " must not be blank");
    }
    return normalized;
  }

  private static <T> T requireOne(List<T> items, String name) {
    if (items.isEmpty()) throw new ApiRequestException(HttpStatus.NOT_FOUND, name + " not found");
    return items.getFirst();
  }

  private Portfolio portfolio(String id, String username, String name, String currency, String cash) {
    List<Position> holdings = positions.stream().filter(position -> position.portfolioId().equals(id)).toList();
    BigDecimal marketValue = holdings.stream().map(Position::marketValue).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal pnl = holdings.stream().map(Position::unrealizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal cashBalance = new BigDecimal(cash);
    return new Portfolio(id, username, name, currency, cashBalance, marketValue,
        cashBalance.add(marketValue), pnl, holdings.size(), AS_OF);
  }

  private Account account(String id, String portfolioId, String type) {
    Portfolio selected = portfolio(portfolioId, null);
    return new Account(id, selected.username(), portfolioId, type, "ACTIVE", selected.currency(),
        selected.cashBalance(), selected.cashBalance(), selected.totalValue(), AS_OF);
  }

  private static Position position(
      String id, String portfolioId, String issuerId, String symbol, String assetClass,
      String currency, String quantity, String averageCost, String price
  ) {
    BigDecimal units = new BigDecimal(quantity);
    BigDecimal cost = new BigDecimal(averageCost);
    BigDecimal marketPrice = new BigDecimal(price);
    return new Position(id, portfolioId, issuerId, symbol, assetClass, currency, units, cost,
        marketPrice, units.multiply(marketPrice), units.multiply(marketPrice.subtract(cost)), AS_OF);
  }

  private static Transaction transaction(
      String id, String portfolioId, String issuerId, String symbol, String side,
      String currency, String quantity, String price, String executedAt
  ) {
    BigDecimal units = new BigDecimal(quantity);
    BigDecimal executionPrice = new BigDecimal(price);
    return new Transaction(id, portfolioId, issuerId, symbol, side, "SETTLED", currency,
        units, executionPrice, units.multiply(executionPrice), executedAt);
  }

  public record User(String id, String username, String displayName) {}

  public record Portfolio(
      String id, String username, String name, String currency, BigDecimal cashBalance,
      BigDecimal marketValue, BigDecimal totalValue, BigDecimal unrealizedPnl, int positionCount, String asOf
  ) {}

  public record Issuer(String id, String name, String country, String sector, String symbol) {}

  public record Account(
      String id, String username, String portfolioId, String type, String status, String currency,
      BigDecimal cashBalance, BigDecimal buyingPower, BigDecimal equity, String asOf
  ) {}

  public record Position(
      String id, String portfolioId, String issuerId, String symbol, String assetClass, String currency,
      BigDecimal quantity, BigDecimal averageCost, BigDecimal marketPrice,
      BigDecimal marketValue, BigDecimal unrealizedPnl, String asOf
  ) {}

  public record Transaction(
      String id, String portfolioId, String issuerId, String symbol, String side, String status,
      String currency, BigDecimal quantity, BigDecimal price, BigDecimal amount, String executedAt
  ) {}
}
