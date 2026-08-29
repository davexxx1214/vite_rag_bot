package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RagIndexTest {
  @Test
  void returnsChunksInCosineSimilarityOrder() {
    RagChunk matching = chunk("matching", new double[] {1, 0});
    RagChunk partial = chunk("partial", new double[] {1, 1});
    RagChunk opposite = chunk("opposite", new double[] {-1, 0});
    RagIndex index = new RagIndex("test-embedding", 2, List.of(opposite, partial, matching));

    List<RagMatch> matches = index.search(new double[] {1, 0}, 3);

    assertThat(matches).extracting(match -> match.chunk().id())
        .containsExactly("matching", "partial", "opposite");
    assertThat(matches.getFirst().score()).isEqualTo(1.0);
  }

  @Test
  void rejectsQueryVectorsWithTheWrongDimensions() {
    RagIndex index = new RagIndex("test-embedding", 2, List.of(chunk("one", new double[] {1, 0})));

    assertThatThrownBy(() -> index.search(new double[] {1}, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expected 2");
  }

  private static RagChunk chunk(String id, double[] embedding) {
    return new RagChunk(id, id, id, "Section", "https://example.com", "content", embedding);
  }
}
