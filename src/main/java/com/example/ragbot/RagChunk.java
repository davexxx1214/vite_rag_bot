package com.example.ragbot;

public record RagChunk(
    String id,
    String slug,
    String title,
    String section,
    String source,
    String content,
    double[] embedding
) {
  public RagChunk {
    embedding = embedding == null ? new double[0] : embedding.clone();
  }

  @Override
  public double[] embedding() {
    return embedding.clone();
  }

  double[] embeddingView() {
    return embedding;
  }
}
