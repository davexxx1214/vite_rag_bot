package com.example.ragbot;

import java.util.List;

record RagIndexDocument(
    int version,
    String createdAt,
    String embeddingModel,
    int dimensions,
    List<RagChunk> chunks
) {}
