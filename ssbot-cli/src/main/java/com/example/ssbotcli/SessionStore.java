package com.example.ssbotcli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class SessionStore {
  private final Path directory;
  private final ObjectMapper json;
  public SessionStore(Path directory, ObjectMapper json) { this.directory = directory; this.json = json; }
  public String newId() { return UUID.randomUUID().toString(); }
  public List<JsonNode> load(String id) throws IOException {
    JsonNode root = json.readTree(Files.readString(path(id)));
    if (root.path("version").asInt() != 1 || !root.path("items").isArray()) throw new IOException("Invalid session file");
    List<JsonNode> items = new ArrayList<>();
    root.path("items").forEach(items::add);
    return List.copyOf(items);
  }
  public void save(String id, List<JsonNode> items) throws IOException {
    Path target = path(id);
    Files.createDirectories(directory);
    Path temp = Files.createTempFile(directory, "session-", ".tmp");
    try {
      Files.writeString(temp, json.writeValueAsString(Map.of("version", 1, "items", items)));
      try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
    } finally { Files.deleteIfExists(temp); }
  }
  private Path path(String id) {
    if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      throw new IllegalArgumentException("Invalid session ID");
    return directory.resolve(id + ".json");
  }
}
