package com.example.ragbot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Existing single-attempt synthesis/fallback policy, separated from the model/tool loop. */
final class AgentRecovery {
  private static final Logger LOGGER = LoggerFactory.getLogger(AgentRecovery.class);
  private final AgentProperties properties;
  private final RagProperties rag;
  private final Model model;
  private final ContextManager context;
  private final ToolRegistry tools;
  private final ObjectMapper mapper;

  AgentRecovery(AgentProperties properties, RagProperties rag, Model model, ContextManager context,
      ToolRegistry tools, ObjectMapper mapper) {
    this.properties = properties;
    this.rag = rag;
    this.model = model;
    this.context = context;
    this.tools = tools;
    this.mapper = mapper;
  }

  AgentRun.Answer recover(String message, AgentLoop.Context input, AgentRun state, AgentProgress progress,
      RunControl control, Runnable enterFinal) throws IOException {
    String answer = partialAnswer(message, state.completedQueries);
    if (!state.finalRequested) {
      try {
        enterFinal.run();
        control.beginFinal();
        progress.emit(new AgentEvent.AnswerReset());
        progress.start("recovery", "查询已停止，正在整理已取得的结果…", "已取得 " + state.successfulResults.size() + " 份工具结果");
        String instructions = input.instructions()
            + "\nThe task stopped early. Use ONLY these successful results. Clearly identify unanswered parts; do not claim completion.";
        var recoveryInput = List.of(context.message("user", message),
            context.message("user", "Successful tool results:\n" + recoveredResults(state.successfulResults)));
        if (context.estimate(instructions, tools.definitions(), recoveryInput) >= properties.inputLimit(rag.chatModel()))
          throw new IOException("Recovery context exceeds its budget");
        var recovered = model.respond(instructions, recoveryInput, tools.tools(), false, delta -> {
          control.check(); progress.emit(new AgentEvent.Delta(delta));
        }, control);
        if (!recovered.calls().isEmpty() || recovered.text().isBlank()) throw new IOException("Invalid recovery answer");
        answer = recovered.text() + partialNotice(message);
        state.transcript.addAll(recovered.output());
      } catch (IOException | InterruptedException | RuntimeException error) {
        Thread.interrupted();
        LOGGER.warn("Partial-answer synthesis unavailable ({})", error.getClass().getSimpleName());
      }
    }
    return new AgentRun.Answer(answer, false);
  }

  private String recoveredResults(List<JsonNode> results) {
    List<JsonNode> facts = new ArrayList<>();
    int budget = Math.min(8_000, properties.inputLimit(rag.chatModel()) / 4);
    int share = Math.max(100, (budget - 100) / results.size());
    for (JsonNode result : results) {
      if (TokenBudget.estimate(result) <= share) { facts.add(result); continue; }
      String preview = mapper.writeValueAsString(result);
      JsonNode snippet;
      do {
        preview = MemoryStore.excerpt(preview, Math.max(1, preview.length() / 2));
        snippet = mapper.valueToTree(Map.of("preview", preview, "truncated", true,
            "note", "Only a preview; full tool results remain in this session's history."));
      } while (TokenBudget.estimate(snippet) > share && preview.length() > 2);
      facts.add(snippet);
    }
    return mapper.writeValueAsString(facts);
  }

  static String partialNotice(String message) {
    boolean chinese = message.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    return chinese ? "\n\n_任务未全部完成，已保留现有结果；可以继续提问补充查询。_"
        : "\n\n_The task stopped early. Retrieved results are saved; continue this chat to query the remaining parts._";
  }

  private static String partialAnswer(String message, List<String> completedQueries) {
    boolean chinese = message.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    String notice = chinese ? "任务未全部完成，暂时无法整理回答。已取得的结果保存在会话历史中，可以继续提问。\n\n已完成的查询：\n\n"
        : "The task stopped before an answer could be prepared. Retrieved results are saved in this chat's history; you can continue asking.\n\nCompleted queries:\n\n";
    // Plain labels only: no tool payloads, arbitrary arguments, or Markdown supplied by a tool.
    return notice + completedQueries.stream().distinct()
        .map(query -> "- " + query.replaceAll("[\\\\`*{}\\[\\]<>!#|]", ""))
        .collect(java.util.stream.Collectors.joining("\n"));
  }

}
