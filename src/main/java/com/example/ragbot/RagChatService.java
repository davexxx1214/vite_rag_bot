package com.example.ragbot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "rag.build-index", havingValue = "false", matchIfMissing = true)
public class RagChatService {
  static final String INSTRUCTIONS = """
      You are a concise help-center assistant. Answer using only the supplied knowledge-base excerpts. Reply in the same language as the user's current question. Use concise GitHub-Flavored Markdown when it improves readability: short headings, bullet or numbered lists, bold text, inline code, and fenced code blocks. Cite supporting excerpts inline as [S1], [S2], and so on, and keep citation markers outside code formatting. If the excerpts do not contain enough information, say that the local help documents do not cover it. Never invent API behavior, URLs, or trading rules.
      """.trim();

  private static final Logger LOGGER = LoggerFactory.getLogger(RagChatService.class);
  private final RagIndex ragIndex;
  private final OpenAiClient openAi;

  public RagChatService(RagIndex ragIndex, OpenAiClient openAi) {
    this.ragIndex = ragIndex;
    this.openAi = openAi;
  }

  public RagRetrieval retrieve(String message) throws IOException, InterruptedException {
    long startedAt = System.currentTimeMillis();
    double[] queryEmbedding = openAi.embed(message, ragIndex.embeddingModel());
    List<RagMatch> matches = ragIndex.search(queryEmbedding, 5);
    LOGGER.info("RAG retrieval completed in {}ms", System.currentTimeMillis() - startedAt);

    List<RagSource> sources = new ArrayList<>();
    StringBuilder context = new StringBuilder();
    for (int index = 0; index < matches.size(); index++) {
      RagMatch match = matches.get(index);
      RagChunk chunk = match.chunk();
      String citation = "S" + (index + 1);
      if (!context.isEmpty()) context.append("\n\n---\n\n");
      context.append('[').append(citation).append("] ")
          .append(chunk.title()).append(" — ").append(chunk.section()).append('\n')
          .append("Source: ").append(chunk.source()).append('\n')
          .append(chunk.content());
      sources.add(new RagSource(
          citation,
          chunk.title(),
          chunk.section(),
          chunk.source(),
          Math.round(match.score() * 10_000d) / 10_000d
      ));
    }
    return new RagRetrieval(context.toString(), List.copyOf(sources));
  }

  public void streamAnswer(
      RagRetrieval retrieval,
      List<ChatMessage> history,
      String message,
      OpenAiClient.TextSink sink
  ) throws IOException, InterruptedException {
    String conversation = history.isEmpty()
        ? "No earlier messages."
        : String.join(
            "\n",
            history.stream()
                .map(item -> (item.role().equals("user") ? "User: " : "Assistant: ") + item.content())
                .toList()
        );
    String modelInput = "Knowledge-base excerpts:\n\n" + retrieval.context()
        + "\n\nRecent conversation:\n" + conversation
        + "\n\nCurrent user question:\n" + message;
    openAi.streamAnswer(INSTRUCTIONS, modelInput, sink);
  }
}
