package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HelpDocsTest {
  @Test
  void loadsFrontmatterAndSortsDocuments(@TempDir Path directory) throws IOException {
    Files.writeString(
        directory.resolve("second.md"),
        """
        ---
        slug: second
        title: Second
        order: 2
        ---
        Second body
        """,
        StandardCharsets.UTF_8
    );
    Files.writeString(
        directory.resolve("first.md"),
        """
        ---
        slug: first
        title: First
        summary: A summary
        order: 1
        ---
        First body
        """,
        StandardCharsets.UTF_8
    );

    List<HelpDoc> docs = HelpDocs.load(directory);

    assertThat(docs).extracting(HelpDoc::slug).containsExactly("first", "second");
    assertThat(docs.getFirst().title()).isEqualTo("First");
    assertThat(docs.getFirst().summary()).isEqualTo("A summary");
    assertThat(docs.getFirst().content()).isEqualTo("First body");
  }
}
