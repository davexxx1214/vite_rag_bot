package com.example.ragbot;

import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** A deterministic inline table/chart from the post-edit GET, never model arithmetic. */
final class PortfolioEditView {
  private PortfolioEditView() {}

  static String markdown(JsonNode result) {
    if (!List.of("applied", "no_change").contains(result.path("status").asString()) || !result.has("verification")) return "";
    JsonNode verified = result.path("verification");
    String state = verified.path("status").asString();
    StringBuilder text = new StringBuilder("### 编辑后的查询结果\n\n");
    if (state.equals("unavailable")) return text.append("修改回执已保存，追加查询未完成。当前名称、估值和图表尚未核验；可再次查询，无需重新修改。\n").toString();
    if (!state.equals("confirmed") && !state.equals("changed")) return "";
    if (state.equals("changed")) text.append("修改已经成功，但查询时对象又发生了变化。下表展示查询到的当前状态。\n\n");
    else text.append("已重新查询并核验名称和版本。\n\n");
    JsonNode portfolio = verified.path("portfolio");
    String currency = portfolio.path("currency").asString();
    text.append("| 字段 | 查询结果 |\n| --- | --- |\n");
    for (String[] field : List.of(new String[]{"投资组合", "id"}, new String[]{"当前名称", "name"},
        new String[]{"版本", "version"}, new String[]{"币种", "currency"}, new String[]{"持仓数", "positionCount"}))
      row(text, field[0], portfolio.path(field[1]).asString());
    for (String[] field : List.of(new String[]{"现金余额", "cashBalance"}, new String[]{"持仓市值", "marketValue"},
        new String[]{"总资产", "totalValue"}, new String[]{"未实现盈亏", "unrealizedPnl"}))
      row(text, field[0], amount(portfolio, field[1]) + " " + currency);
    row(text, "估值日期", portfolio.path("asOf").asString());
    row(text, "编辑时间", portfolio.path("updatedAt").asString());
    row(text, "查询时间", verified.path("queriedAt").asString());
    BigDecimal cash = new BigDecimal(amount(portfolio, "cashBalance"));
    BigDecimal market = new BigDecimal(amount(portfolio, "marketValue"));
    BigDecimal total = new BigDecimal(amount(portfolio, "totalValue"));
    if (total.signum() > 0 && cash.signum() >= 0 && market.signum() >= 0 && cash.add(market).compareTo(total) == 0) {
      text.append("\n#### 资产构成（").append(currency).append("）\n\n```limits\n")
          .append("现金余额 | ").append(cash.toPlainString()).append(" / ").append(total.toPlainString()).append(" | ").append(currency)
          .append("\n持仓市值 | ").append(market.toPlainString()).append(" / ").append(total.toPlainString()).append(" | ").append(currency)
          .append("\n```\n");
    } else text.append("\n当前金额不适合绘制资产占比图；请以上表为准。\n");
    text.append("\n以上为 mock 数据及本次查询快照。[查询来源](")
        .append(verified.path("source").asString().replace(")", "%29")).append(")\n");
    return text.toString();
  }

  private static String amount(JsonNode portfolio, String field) { return new BigDecimal(portfolio.path(field).asString()).toPlainString(); }
  private static void row(StringBuilder text, String label, String value) { text.append("| ").append(label).append(" | ").append(escape(value)).append(" |\n"); }
  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\\", "\\\\")
        .replace("|", "\\|").replace("`", "\\`").replace("*", "\\*").replace("_", "\\_")
        .replace("[", "\\[").replace("]", "\\]").replace("\r", " ").replace("\n", " ");
  }
}
