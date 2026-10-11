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
  private static final Logger LOGGER = LoggerFactory.getLogger(RagChatService.class);
  private final RagIndex ragIndex;
  private final OpenAiClient openAi;

  public RagChatService(RagIndex ragIndex, OpenAiClient openAi) {
    this.ragIndex = ragIndex;
    this.openAi = openAi;
  }

  public RagRetrieval retrieve(String message) throws IOException, InterruptedException {
    return retrieve(message, null);
  }

  public RagRetrieval retrieve(String message, RunControl control) throws IOException, InterruptedException {
    long startedAt = System.currentTimeMillis();
    double[] queryEmbedding = control == null ? openAi.embed(message, ragIndex.embeddingModel())
        : openAi.embed(message, ragIndex.embeddingModel(), control);
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

}
