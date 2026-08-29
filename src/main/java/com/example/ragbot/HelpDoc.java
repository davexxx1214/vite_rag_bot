package com.example.ragbot;

public record HelpDoc(
    String slug,
    String title,
    String summary,
    String source,
    int order,
    String content
) {}
