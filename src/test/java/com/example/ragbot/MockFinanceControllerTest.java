package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MockFinanceControllerTest {
  private MockFinanceService finance;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    finance = new MockFinanceService();
    mvc = MockMvcBuilders.standaloneSetup(new MockFinanceController(finance))
        .setControllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test
  void listsUsersAndAllPortfolios() throws Exception {
    mvc.perform(get("/api/mock/users"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].username").value("alice"));
    mvc.perform(get("/api/mock/portfolios"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3002));
  }

  @ParameterizedTest
  @CsvSource({"portfolios,pf-001,3000", "issuers,iss-001,3", "accounts,acc-001,2"})
  void filtersListsByUsernameAndId(String resource, String id, int count) throws Exception {
    mvc.perform(get("/api/mock/" + resource).param("username", " ALICE "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(count));
    mvc.perform(get("/api/mock/" + resource).param("id", id).param("username", "alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(id));
    mvc.perform(get("/api/mock/" + resource).param("username", "unknown"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/mock/" + resource).param("id", "missing"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @ParameterizedTest
  @CsvSource({"portfolios,pf-001", "issuers,iss-002", "accounts,acc-001"})
  void detailsRespectIdAndUsername(String resource, String id) throws Exception {
    mvc.perform(get("/api/mock/" + resource + "/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));
    mvc.perform(get("/api/mock/" + resource + "/" + id).param("username", "alice"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/mock/" + resource + "/" + id).param("username", "bob"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").exists());
    mvc.perform(get("/api/mock/" + resource + "/missing"))
        .andExpect(status().isNotFound());
  }

  @Test
  void issuersFollowHoldingsIncludingSharedIssuers() throws Exception {
    mvc.perform(get("/api/mock/issuers").param("username", "bob"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value("iss-001"))
        .andExpect(jsonPath("$[1].id").value("iss-004"));
    mvc.perform(get("/api/mock/issuers/iss-001").param("username", "alice"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/mock/issuers/iss-001").param("username", "bob"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/mock/issuers").param("username", "charlie"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void returnsPositionsAndTradesForTheSelectedPortfolio() throws Exception {
    mvc.perform(get("/api/mock/portfolios/pf-001/positions").param("username", "alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].issuerId").value("iss-001"))
        .andExpect(jsonPath("$[0].marketValue").value(2400))
        .andExpect(jsonPath("$[1].unrealizedPnl").value(-50));
    mvc.perform(get("/api/mock/portfolios/pf-003/transactions").param("username", "bob"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[2].side").value("SELL"));
  }

  @ParameterizedTest
  @CsvSource({"positions", "transactions"})
  void distinguishesEmptyPortfoliosFromMissingOrMismatchedPortfolios(String resource) throws Exception {
    mvc.perform(get("/api/mock/portfolios/pf-004/" + resource).param("username", "charlie"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/mock/portfolios/pf-001/" + resource).param("username", "bob"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/mock/portfolios/missing/" + resource))
        .andExpect(status().isNotFound());
  }

  @ParameterizedTest
  @CsvSource({"portfolios", "issuers", "accounts"})
  void rejectsBlankFilters(String resource) throws Exception {
    mvc.perform(get("/api/mock/" + resource).param("username", " "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("username must not be blank"));
    mvc.perform(get("/api/mock/" + resource).param("id", " "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("id must not be blank"));
  }

  @Test
  void financialTotalsAndRelationsAreConsistent() {
    for (MockFinanceService.Portfolio portfolio : finance.portfolios(null, null)) {
      var holdings = finance.positions(portfolio.id(), portfolio.username());
      assertThat(portfolio.positionCount()).isEqualTo(holdings.size());
      assertThat(portfolio.marketValue()).isEqualByComparingTo(holdings.stream()
          .map(MockFinanceService.Position::marketValue).reduce(BigDecimal.ZERO, BigDecimal::add));
      assertThat(portfolio.totalValue()).isEqualByComparingTo(portfolio.cashBalance().add(portfolio.marketValue()));
      assertThat(portfolio.unrealizedPnl()).isEqualByComparingTo(holdings.stream()
          .map(MockFinanceService.Position::unrealizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add));
      for (MockFinanceService.Position position : holdings) {
        assertThat(position.currency()).isEqualTo(portfolio.currency());
        assertThat(finance.issuer(position.issuerId(), portfolio.username()).symbol()).isEqualTo(position.symbol());
        BigDecimal netQuantity = finance.transactions(portfolio.id(), null).stream()
            .filter(transaction -> transaction.symbol().equals(position.symbol()))
            .map(transaction -> transaction.side().equals("BUY") ? transaction.quantity() : transaction.quantity().negate())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(position.quantity()).isEqualByComparingTo(netQuantity);
      }
    }
    for (MockFinanceService.Account account : finance.accounts(null, null)) {
      var portfolio = finance.portfolio(account.portfolioId(), account.username());
      assertThat(account.equity()).isEqualByComparingTo(portfolio.totalValue());
      assertThat(account.cashBalance()).isEqualByComparingTo(portfolio.cashBalance());
    }
  }
}
