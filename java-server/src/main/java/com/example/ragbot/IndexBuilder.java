package com.example.ragbot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class IndexBuilder {
  private static final Pattern HEADING = Pattern.compile("^#{2,3}\\s+(.+)$");
  private static final int MAX_CHARACTERS = 1_800;

  private IndexBuilder() {}

  static void build(
      Path projectRoot,
      AppConfig config,
      List<HelpDoc> docs,
      OpenAiClient openAi
  ) throws IOException, InterruptedException {
    List<ChunkDraft> drafts = docs.stream().flatMap(doc -> chunkDocument(doc).stream()).toList();
    if (drafts.isEmpty()) throw new IllegalStateException("No document chunks were generated");

    System.out.printf("Embedding %d chunks from %d documents...%n", drafts.size(), docs.size());
    List<String> inputs = drafts.stream()
        .map(chunk -> chunk.title() + "\n" + chunk.section() + "\n" + chunk.content())
        .toList();
    List<double[]> embeddings = openAi.embedTexts(inputs, config.embeddingModel());

    List<RagChunk> chunks = new ArrayList<>();
    for (int index = 0; index < drafts.size(); index++) {
      ChunkDraft draft = drafts.get(index);
      chunks.add(new RagChunk(
          draft.id(),
          draft.slug(),
          draft.title(),
          draft.section(),
          draft.source(),
          draft.content(),
          embeddings.get(index)
      ));
    }

    RagIndex ragIndex = new RagIndex(
        1,
        Instant.now().toString(),
        config.embeddingModel(),
        chunks.getFirst().embedding().length,
        chunks
    );
    Path outputPath = projectRoot.resolve("data").resolve("rag-index.json");
    Files.createDirectories(outputPath.getParent());
    Files.writeString(
        outputPath,
        Json.stringify(ragIndex.toMap()) + "\n",
        StandardCharsets.UTF_8
    );
    System.out.println("Wrote " + outputPath);
    System.out.printf(
        "Model: %s; dimensions: %d%n",
        ragIndex.embeddingModel(),
        ragIndex.dimensions()
    );
  }

  private static List<ChunkDraft> chunkDocument(HelpDoc doc) {
    List<Section> sections = new ArrayList<>();
    String heading = "Overview";
    StringBuilder buffer = new StringBuilder();

    for (String line : doc.content().split("\\R", -1)) {
      Matcher match = HEADING.matcher(line);
      if (match.matches()) {
        flushSection(sections, heading, buffer);
        heading = match.group(1).trim();
      } else {
        if (!buffer.isEmpty()) buffer.append('\n');
        buffer.append(line);
      }
    }
    flushSection(sections, heading, buffer);

    List<ChunkDraft> chunks = new ArrayList<>();
    int number = 0;
    for (Section section : sections) {
      for (String content : splitLongText(section.content())) {
        number++;
        chunks.add(new ChunkDraft(
            doc.slug() + "-" + String.format("%02d", number),
            doc.slug(),
            doc.title(),
            section.heading(),
            doc.source(),
            content
        ));
      }
    }
    return chunks;
  }

  private static void flushSection(
      List<Section> sections,
      String heading,
      StringBuilder buffer
  ) {
    String content = buffer.toString().trim();
    if (!content.isEmpty()) sections.add(new Section(heading, content));
    buffer.setLength(0);
  }

  private static List<String> splitLongText(String text) {
    if (text.length() <= MAX_CHARACTERS) return List.of(text);
    List<String> chunks = new ArrayList<>();
    StringBuilder current = new StringBuilder();

    for (String paragraph : text.split("\\n{2,}")) {
      if (!current.isEmpty() && current.length() + paragraph.length() + 2 > MAX_CHARACTERS) {
        chunks.add(current.toString().trim());
        current.setLength(0);
      }
      if (paragraph.length() > MAX_CHARACTERS) {
        for (int start = 0; start < paragraph.length(); start += MAX_CHARACTERS) {
          String part = paragraph.substring(start, Math.min(start + MAX_CHARACTERS, paragraph.length())).trim();
          if (!part.isEmpty()) chunks.add(part);
        }
      } else {
        if (!current.isEmpty()) current.append("\n\n");
        current.append(paragraph);
      }
    }
    if (!current.toString().trim().isEmpty()) chunks.add(current.toString().trim());
    return chunks;
  }

  private record Section(String heading, String content) {}

  private record ChunkDraft(
      String id,
      String slug,
      String title,
      String section,
      String source,
      String content
  ) {}
}
