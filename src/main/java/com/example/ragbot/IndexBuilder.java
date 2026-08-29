package com.example.ragbot;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class IndexBuilder {
  private static final Logger LOGGER = LoggerFactory.getLogger(IndexBuilder.class);
  private static final Pattern HEADING = Pattern.compile("^#{2,3}\\s+(.+)$");
  private static final int MAX_CHARACTERS = 1_800;

  private final RagProperties properties;
  private final HelpDocumentService helpDocuments;
  private final OpenAiClient openAi;
  private final ObjectMapper objectMapper;

  public IndexBuilder(
      RagProperties properties,
      HelpDocumentService helpDocuments,
      OpenAiClient openAi,
      ObjectMapper objectMapper
  ) {
    this.properties = properties;
    this.helpDocuments = helpDocuments;
    this.openAi = openAi;
    this.objectMapper = objectMapper;
  }

  public void build() throws IOException, InterruptedException {
    List<HelpDoc> docs = helpDocuments.documents();
    List<ChunkDraft> drafts = docs.stream().flatMap(doc -> chunkDocument(doc).stream()).toList();
    if (drafts.isEmpty()) throw new IllegalStateException("No document chunks were generated");

    LOGGER.info("Embedding {} chunks from {} documents", drafts.size(), docs.size());
    List<String> inputs = drafts.stream()
        .map(chunk -> chunk.title() + "\n" + chunk.section() + "\n" + chunk.content())
        .toList();
    List<double[]> embeddings = openAi.embedTexts(inputs, properties.embeddingModel());

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

    RagIndexDocument document = new RagIndexDocument(
        1,
        Instant.now().toString(),
        properties.embeddingModel(),
        chunks.getFirst().embeddingView().length,
        chunks
    );
    writeAtomically(properties.indexPath().toAbsolutePath().normalize(), document);
    LOGGER.info(
        "Wrote {} using {} dimensions from model {}",
        properties.indexPath(),
        document.dimensions(),
        document.embeddingModel()
    );
  }

  private void writeAtomically(Path output, RagIndexDocument document) throws IOException {
    Path parent = output.getParent();
    if (parent != null) Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, "rag-index-", ".json.tmp");
    try {
      objectMapper.writeValue(temporary.toFile(), document);
      try {
        Files.move(
            temporary,
            output,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        );
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  static List<ChunkDraft> chunkDocument(HelpDoc doc) {
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
    return List.copyOf(chunks);
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
          String part = paragraph.substring(
              start,
              Math.min(start + MAX_CHARACTERS, paragraph.length())
          ).trim();
          if (!part.isEmpty()) chunks.add(part);
        }
      } else {
        if (!current.isEmpty()) current.append("\n\n");
        current.append(paragraph);
      }
    }
    String remaining = current.toString().trim();
    if (!remaining.isEmpty()) chunks.add(remaining);
    return List.copyOf(chunks);
  }

  private record Section(String heading, String content) {}

  record ChunkDraft(
      String id,
      String slug,
      String title,
      String section,
      String source,
      String content
  ) {}
}
