package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FinanceEditingTest {
  private MockFinanceService finance;
  private MockMvc mvc;
  @BeforeEach void setup() {
    finance = new MockFinanceService();
    mvc = MockMvcBuilders.standaloneSetup(new MockFinanceController(finance))
        .setControllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test void patchChangesReadsAndReturnsAnIdempotentReceipt() throws Exception {
    String body = "{\"name\":\" 长期投资 \",\"expectedVersion\":1}";
    mvc.perform(patch("/api/mock/portfolios/pf-001").param("username", "alice")
        .header("Idempotency-Key", "op-1").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("applied"))
        .andExpect(jsonPath("$.portfolio.version").value(2)).andExpect(jsonPath("$.changes.name.before").value("US Growth"));
    mvc.perform(get("/api/mock/portfolios/pf-001").param("username", "alice"))
        .andExpect(jsonPath("$.name").value("长期投资")).andExpect(jsonPath("$.asOf").value("2026-10-09T16:00:00Z"));
    mvc.perform(patch("/api/mock/portfolios/pf-001").param("username", "alice")
        .header("Idempotency-Key", "op-1").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andExpect(jsonPath("$.portfolio.version").value(2));
    mvc.perform(get("/api/mock/operations/op-1").param("username", "alice"))
        .andExpect(jsonPath("$.status").value("applied"));
    mvc.perform(get("/api/mock/operations/op-1").param("username", "bob"))
        .andExpect(jsonPath("$.status").value("not_found"));
    assertThat(finance.portfolio("pf-001", "alice").totalValue()).isEqualByComparingTo("5000");
    assertThat(finance.portfolios(null, "alice").getFirst().name()).isEqualTo("长期投资");
  }

  @Test void duplicateVersionAndIdempotencyConflictsNeverChangeData() {
    assertThatThrownBy(() -> finance.updatePortfolio("pf-001", "alice", "dup",
        new MockFinanceService.PortfolioEdit(" hong kong INCOME ", 1L)))
        .isInstanceOfSatisfying(ApiRequestException.class, error -> assertThat(error.body()).containsEntry("code", "DUPLICATE_VALUE"));
    finance.updatePortfolio("pf-001", "alice", "ok", new MockFinanceService.PortfolioEdit("Retirement", 1L));
    assertThatThrownBy(() -> finance.updatePortfolio("pf-001", "alice", "stale", new MockFinanceService.PortfolioEdit("Other", 1L)))
        .isInstanceOfSatisfying(ApiRequestException.class, error -> assertThat(error.body()).containsEntry("code", "VERSION_CONFLICT"));
    assertThatThrownBy(() -> finance.updatePortfolio("pf-001", "alice", "ok", new MockFinanceService.PortfolioEdit("Other", 2L)))
        .isInstanceOfSatisfying(ApiRequestException.class, error -> assertThat(error.body()).containsEntry("code", "IDEMPOTENCY_CONFLICT"));
    assertThat(finance.portfolio("pf-001", "alice").name()).isEqualTo("Retirement");
    assertThat(finance.portfolio("pf-001", "alice").version()).isEqualTo(2);
  }

  @Test void noOpAndNamesAreScopedToTheirOwner() throws Exception {
    assertThat(finance.updatePortfolio("pf-001", "alice", "noop", new MockFinanceService.PortfolioEdit("US Growth", 1L)))
        .containsEntry("status", "no_change");
    finance.updatePortfolio("pf-003", "bob", "shared-name", new MockFinanceService.PortfolioEdit("US Growth", 1L));
    assertThat(finance.portfolio("pf-001", "alice").version()).isEqualTo(1);
    mvc.perform(patch("/api/mock/portfolios/pf-001").param("username", "bob").header("Idempotency-Key", "foreign")
        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Foreign\",\"expectedVersion\":1}"))
        .andExpect(status().isNotFound());
  }

  @Test void rejectsInvalidAndNonEditableFields() throws Exception {
    for (String body : List.of("{\"name\":\"\",\"expectedVersion\":1}",
        "{\"name\":\"Valid\",\"expectedVersion\":1.5}",
        "{\"name\":\"Valid\",\"expectedVersion\":1,\"cashBalance\":9000}", "{}")) {
      mvc.perform(patch("/api/mock/portfolios/pf-001").param("username", "alice").header("Idempotency-Key", "bad")
          .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    assertThat(finance.portfolio("pf-001", "alice").version()).isEqualTo(1);
  }

  @Test void concurrentDuplicateWritesHaveExactlyOneWinner() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Callable<Boolean>> writes = List.of(() -> attempt("pf-001", "a"), () -> attempt("pf-002", "b"));
      var outcomes = executor.invokeAll(writes);
      assertThat(List.of(outcomes.get(0).get(), outcomes.get(1).get())).containsExactlyInAnyOrder(true, false);
      assertThat(finance.portfolios(null, "alice").stream().filter(p -> p.name().equals("Same"))).hasSize(1);
    }
  }
  private boolean attempt(String id, String key) {
    try { finance.updatePortfolio(id, "alice", key, new MockFinanceService.PortfolioEdit("Same", 1L)); return true; }
    catch (ApiRequestException error) { assertThat(error.body()).containsEntry("code", "DUPLICATE_VALUE"); return false; }
  }
}
