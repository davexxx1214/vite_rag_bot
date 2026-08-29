package com.example.ragbot;

public record RagSource(
    String citation,
    String title,
    String section,
    String source,
    double score
) {}
