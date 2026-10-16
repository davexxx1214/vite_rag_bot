package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

class PortfolioEditViewTest {
  private final JsonMapper mapper = new JsonMapper();
  @Test void escapesNamesAndPreservesPreciseAmountsAndDates() {
    ObjectNode result = result();
    var portfolio = (ObjectNode) result.path("verification").path("portfolio");
    portfolio.put("name", "| *name* <script>bad</script> `code`");
    portfolio.put("cashBalance", new java.math.BigDecimal("12345678901234567890.123456789"));
    portfolio.put("marketValue", java.math.BigDecimal.ZERO);
    portfolio.put("totalValue", new java.math.BigDecimal("12345678901234567890.123456789"));
    String view = PortfolioEditView.markdown(result);
    assertThat(view).contains("\\| \\*name\\* &lt;script&gt;", "12345678901234567890.123456789 USD", "2026-10-09T16:00:00Z")
        .doesNotContain("<script>");
  }
  @Test void zeroNegativeAndInconsistentTotalsDoNotCreateMisleadingCompositionCharts() {
    ObjectNode result = result();
    var portfolio = (ObjectNode) result.path("verification").path("portfolio");
    portfolio.put("totalValue", 0);
    assertThat(PortfolioEditView.markdown(result)).contains("不适合绘制").doesNotContain("```limits");
    portfolio.put("totalValue", 5000); portfolio.put("cashBalance", -1);
    assertThat(PortfolioEditView.markdown(result)).doesNotContain("```limits");
    portfolio.put("cashBalance", 100);
    assertThat(PortfolioEditView.markdown(result)).doesNotContain("```limits");
  }
  @Test void cancelledAndRejectedEditsHaveNoQueryView() {
    for (String status : new String[]{"cancelled", "waiting_for_user", "rejected"}) {
      var result = result(); result.put("status", status);
      assertThat(PortfolioEditView.markdown(result)).isEmpty();
    }
  }
  private ObjectNode result() {
    var portfolio = new MockFinanceService().portfolio("pf-001", "alice");
    return (ObjectNode) mapper.valueToTree(Map.of("status", "applied", "operationId", "op-test", "verification",
        Map.of("status", "confirmed", "portfolio", portfolio, "queriedAt", "2026-10-14T00:00:00Z", "source", "/api/mock/portfolios/pf-001?username=alice")));
  }
}
