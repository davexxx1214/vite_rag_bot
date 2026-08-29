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
import java.util.stream.Stream;

final class HelpDocs {
  private HelpDocs() {}

  static List<HelpDoc> load(Path docsDirectory) throws IOException {
    Path directory = docsDirectory.toAbsolutePath().normalize();
    if (!Files.isDirectory(directory)) {
      throw new IOException("Help document directory does not exist: " + directory);
    }

    List<HelpDoc> docs = new ArrayList<>();
    try (Stream<Path> files = Files.list(directory)) {
      for (Path path : files.filter(HelpDocs::isMarkdown).toList()) {
        String filename = path.getFileName().toString();
        String fallbackSlug = filename.substring(0, filename.length() - 3);
        docs.add(parse(Files.readString(path, StandardCharsets.UTF_8), fallbackSlug));
      }
    }
    docs.sort(Comparator.comparingInt(HelpDoc::order).thenComparing(HelpDoc::slug));
    return List.copyOf(docs);
  }

  private static boolean isMarkdown(Path path) {
    return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".md");
  }

  static HelpDoc parse(String raw, String fallbackSlug) {
    String normalized = raw.replace("\r\n", "\n");
    Map<String, String> metadata = new LinkedHashMap<>();
    String content = normalized.trim();

    if (normalized.startsWith("---\n")) {
      int end = normalized.indexOf("\n---", 4);
      if (end >= 0) {
        String frontmatter = normalized.substring(4, end);
        for (String line : frontmatter.split("\n")) {
          int separator = line.indexOf(':');
          if (separator <= 0) continue;
          metadata.put(
              line.substring(0, separator).trim(),
              unquote(line.substring(separator + 1).trim())
          );
        }
        int contentStart = end + 4;
        if (contentStart < normalized.length() && normalized.charAt(contentStart) == '\n') {
          contentStart++;
        }
        content = normalized.substring(contentStart).trim();
      }
    }

    return new HelpDoc(
        metadata.getOrDefault("slug", fallbackSlug),
        metadata.getOrDefault("title", fallbackSlug),
        metadata.getOrDefault("summary", ""),
        metadata.getOrDefault("source", ""),
        parseOrder(metadata.get("order")),
        content
    );
  }

  private static int parseOrder(String value) {
    if (value == null) return 999;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return 999;
    }
  }

  private static String unquote(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }
}
