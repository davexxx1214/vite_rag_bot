package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IndexBuilderTest {
  @Test
  void createsSectionAwareChunks() {
    HelpDoc doc = new HelpDoc(
        "guide",
        "Guide",
        "",
        "https://example.com",
        1,
        "Overview text\n\n## Authentication\n\nUse an API key.\n\n### Domains\n\nUse the paper host."
    );

    List<IndexBuilder.ChunkDraft> chunks = IndexBuilder.chunkDocument(doc);

    assertThat(chunks).extracting(IndexBuilder.ChunkDraft::section)
        .containsExactly("Overview", "Authentication", "Domains");
    assertThat(chunks).extracting(IndexBuilder.ChunkDraft::id)
        .containsExactly("guide-01", "guide-02", "guide-03");
  }
}
