package com.example.ragbot;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class RagIndex {
  private final String embeddingModel;
  private final int dimensions;
  private final List<RagChunk> chunks;

  RagIndex(String embeddingModel, int dimensions, List<RagChunk> chunks) {
    if (embeddingModel == null || embeddingModel.isBlank()) {
      throw new IllegalArgumentException("The RAG index has no embedding model");
    }
    if (dimensions <= 0) throw new IllegalArgumentException("The RAG index dimensions are invalid");
    if (chunks == null || chunks.isEmpty()) throw new IllegalArgumentException("The RAG index has no chunks");
    for (RagChunk chunk : chunks) {
      if (chunk.embeddingView().length != dimensions) {
        throw new IllegalArgumentException("RAG chunk " + chunk.id() + " has the wrong dimensions");
      }
      for (double coordinate : chunk.embeddingView()) {
        if (!Double.isFinite(coordinate)) {
          throw new IllegalArgumentException("RAG chunk " + chunk.id() + " has a non-finite vector");
        }
      }
    }
    this.embeddingModel = embeddingModel;
    this.dimensions = dimensions;
    this.chunks = List.copyOf(chunks);
  }

  static RagIndex load(Path indexPath, ObjectMapper objectMapper) throws IOException {
    Path path = indexPath.toAbsolutePath().normalize();
    try {
      RagIndexDocument document = objectMapper.readValue(path.toFile(), RagIndexDocument.class);
      return new RagIndex(document.embeddingModel(), document.dimensions(), document.chunks());
    } catch (RuntimeException error) {
      throw new IOException(
          "Could not load " + path + ". Run pnpm build:index first. " + error.getMessage(),
          error
      );
    }
  }

  public List<RagMatch> search(double[] queryEmbedding, int topK) {
    if (queryEmbedding.length != dimensions) {
      throw new IllegalArgumentException(
          "Embedding dimension mismatch: expected " + dimensions + ", received " + queryEmbedding.length
      );
    }
    return chunks.stream()
        .map(chunk -> new RagMatch(chunk, cosineSimilarity(queryEmbedding, chunk.embeddingView())))
        .sorted(Comparator.comparingDouble(RagMatch::score).reversed())
        .limit(Math.max(0, topK))
        .toList();
  }

  public String embeddingModel() {
    return embeddingModel;
  }

  public int dimensions() {
    return dimensions;
  }

  public List<RagChunk> chunks() {
    return chunks;
  }

  private static double cosineSimilarity(double[] left, double[] right) {
    if (left.length != right.length) return -1;
    double dot = 0;
    double leftMagnitude = 0;
    double rightMagnitude = 0;
    for (int index = 0; index < left.length; index++) {
      dot += left[index] * right[index];
      leftMagnitude += left[index] * left[index];
      rightMagnitude += right[index] * right[index];
    }
    double denominator = Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude);
    return denominator == 0 ? -1 : dot / denominator;
  }
}
