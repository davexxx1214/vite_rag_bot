package com.example.ragbot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.ObjectMapper;

/** Two-file commit recovery and one-time legacy migration; no model calls. */
final class CoreMemoryFiles {
  private CoreMemoryFiles() {}

  static MemoryStore.CoreMemory read(Path directory, ObjectMapper mapper) throws IOException {
    Path pending = directory.resolve("core-pending.json");
    if (Files.exists(pending)) finish(directory, mapper.readValue(Files.readString(pending), MemoryStore.CoreMemory.class));
    if (!Files.exists(directory.resolve("USER.md"))) {
      // memory.md and MEMORY.md are the same path on Windows: move legacy content to USER first.
      write(directory, new MemoryStore.CoreMemory(text(directory.resolve("memory.md")), ""), mapper);
    }
    return new MemoryStore.CoreMemory(text(directory.resolve("USER.md")), text(directory.resolve("MEMORY.md")));
  }

  static void write(Path directory, MemoryStore.CoreMemory memory, ObjectMapper mapper) throws IOException {
    MemoryStore.writeAtomic(directory.resolve("core-pending.json"), mapper.writeValueAsString(memory));
    finish(directory, memory);
  }

  private static void finish(Path directory, MemoryStore.CoreMemory memory) throws IOException {
    MemoryStore.writeAtomic(directory.resolve("USER.md"), memory.user().isBlank() ? "" : memory.user().strip() + "\n");
    MemoryStore.writeAtomic(directory.resolve("MEMORY.md"), memory.notes().isBlank() ? "" : memory.notes().strip() + "\n");
    Path legacy = directory.resolve("memory.md"), notes = directory.resolve("MEMORY.md");
    if (Files.exists(legacy) && !Files.isSameFile(legacy, notes)) Files.delete(legacy);
    Files.deleteIfExists(directory.resolve("core-pending.json"));
  }

  private static String text(Path path) throws IOException { return Files.exists(path) ? Files.readString(path) : ""; }
}
