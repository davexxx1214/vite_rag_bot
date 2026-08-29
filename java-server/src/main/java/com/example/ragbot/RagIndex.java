package com.example.ragbot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RagIndex {
  private final int version;
  private final String createdAt;
  private final String embeddingModel;
  private final int dimensions;
  private final List<RagChunk> chunks;

  RagIndex(int version, String createdAt, String embeddingModel, int dimensions, List<RagChunk> chunks) {
    this.version = version;
    this.createdAt = createdAt;
    this.embeddingModel = embeddingModel;
    this.dimensions = dimensions;
    this.chunks = List.copyOf(chunks);
  }

  static RagIndex load(Path projectRoot) throws IOException {
    Path path = projectRoot.resolve("data").resolve("rag-index.json");
    try {
      Map<String, Object> root = Json.object(Json.parse(Files.readString(path, StandardCharsets.UTF_8)));
      List<RagChunk> chunks = new ArrayList<>();
      for (Object item : Json.array(root.get("chunks"))) {
        Map<String, Object> chunk = Json.object(item);
        List<Object> rawEmbedding = Json.array(chunk.get("embedding"));
        double[] embedding = new double[rawEmbedding.size()];
        for (int index = 0; index < rawEmbedding.size(); index++) {
          embedding[index] = Json.number(rawEmbedding.get(index));
        }
        chunks.add(new RagChunk(
            Json.string(chunk.get("id")),
            Json.string(chunk.get("slug")),
            Json.string(chunk.get("title")),
            Json.string(chunk.get("section")),
            Json.string(chunk.get("source")),
            Json.string(chunk.get("content")),
            embedding
        ));
      }
      if (chunks.isEmpty()) throw new IllegalArgumentException("index contains no chunks");
      return new RagIndex(
          (int) Json.number(root.get("version")),
          Json.string(root.get("createdAt")),
          Json.string(root.get("embeddingModel")),
          (int) Json.number(root.get("dimensions")),
          chunks
      );
    } catch (RuntimeException error) {
      throw new IOException(
          "Could not load data/rag-index.json. Run pnpm build:index first. " + error.getMessage(),
          error
      );
    }
  }

  List<RagMatch> search(double[] queryEmbedding, int topK) {
    if (queryEmbedding.length != dimensions) {
      throw new IllegalArgumentException(
          "Embedding dimension mismatch: expected " + dimensions + ", received " + queryEmbedding.length
      );
    }
    return chunks.stream()
        .map(chunk -> new RagMatch(chunk, cosineSimilarity(queryEmbedding, chunk.embedding())))
        .sorted(Comparator.comparingDouble(RagMatch::score).reversed())
        .limit(topK)
        .toList();
  }

  String embeddingModel() {
    return embeddingModel;
  }

  int dimensions() {
    return dimensions;
  }

  List<RagChunk> chunks() {
    return chunks;
  }

  Map<String, Object> toMap() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("version", version);
    result.put("createdAt", createdAt);
    result.put("embeddingModel", embeddingModel);
    result.put("dimensions", dimensions);
    result.put("chunks", chunks.stream().map(RagChunk::toMap).toList());
    return result;
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
