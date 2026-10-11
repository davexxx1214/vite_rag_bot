package com.example.ragbot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Streaming text storage. Indexes are disposable; transcripts and payloads are the source of truth. */
final class FileHistory {
  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_-]{2,}");
  private static final Pattern PAYLOAD = Pattern.compile("[a-f0-9-]{36}\\.json(?:\\.gz)?");
  private final ObjectMapper mapper;
  private final HistoryProperties properties;
  private final Map<Path, Long> reconciled = new HashMap<>();

  FileHistory(ObjectMapper mapper, HistoryProperties properties) {
    this.mapper = mapper;
    this.properties = properties;
  }

  record Metadata(String id, String createdAt, String updatedAt, String title, int turnCount, boolean archived) {}
  record IndexEntry(int turn, String timestamp, String excerpt, List<String> keywords, long byteOffset) {}
  record Page(List<MemoryStore.StoredTurn> turns, int offset, Integer nextOffset, int total) {}
  record SearchPage(List<Map<String, Object>> results, int offset, Integer nextOffset, int total) {}
  record Summary(String text, int through) {}

  Metadata metadata(Path directory) throws IOException {
    Path file = directory.resolve("session.json");
    Path raw = directory.resolve("messages.jsonl");
    long size = Files.exists(raw) ? Files.size(raw) : -1;
    synchronized (reconciled) {
      if (Long.valueOf(size).equals(reconciled.get(directory)) && Files.isRegularFile(directory.resolve("history-index.jsonl")))
        return decode(mapper.readTree(Files.readString(file)));
      JsonNode node = mapper.readTree(Files.readString(file));
      // The size anchor detects a crash between appending a committed line and updating metadata/index.
      if (node.path("turnCount").asInt() > 0 && size == -1 && !Files.exists(directory.resolve("messages.jsonl.gz")))
        throw new IOException("Missing transcript; stored metadata was preserved");
      boolean valid = node.path("indexVersion").asInt() == 2 && node.has("turnCount") && node.path("transcriptBytes").asLong(-2) == size
          && Files.exists(directory.resolve("history-index.jsonl"));
      if (!valid) rebuild(directory);
      reconciled.put(directory, Files.exists(raw) ? Files.size(raw) : -1);
      return decode(mapper.readTree(Files.readString(file)));
    }
  }

  // Metadata contains an extra size anchor, so explicitly decode the public fields.
  private Metadata decode(JsonNode node) {
    return new Metadata(node.path("id").asString(), node.path("createdAt").asString(),
        node.path("updatedAt").asString(), node.path("title").asString(),
        node.path("turnCount").asInt(), node.path("archived").asBoolean());
  }

  Metadata info(Path directory) throws IOException {
    metadata(directory);
    return decode(mapper.readTree(Files.readString(directory.resolve("session.json"))));
  }

  void saveMetadata(Path directory, Metadata metadata) throws IOException {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", metadata.id()); data.put("createdAt", metadata.createdAt());
    data.put("updatedAt", metadata.updatedAt()); data.put("title", metadata.title());
    data.put("turnCount", metadata.turnCount()); data.put("archived", metadata.archived());
    data.put("indexVersion", 2);
    data.put("transcriptBytes", Files.exists(directory.resolve("messages.jsonl"))
        ? Files.size(directory.resolve("messages.jsonl")) : -1);
    data.put("summary", MemoryStore.excerpt(summary(directory).text(), 2000));
    MemoryStore.writeAtomic(directory.resolve("session.json"), mapper.writeValueAsString(data));
  }

  Summary summary(Path directory) throws IOException {
    Path file = directory.resolve("summary.md");
    String text = Files.exists(file) ? Files.readString(file) : "";
    var header = Pattern.compile("\\A<!-- through-turn: (\\d+) -->\\R").matcher(text);
    if (!header.find()) return new Summary(text, 0);
    return new Summary(text.substring(header.end()), Integer.parseInt(header.group(1)));
  }

  void refreshSummary(Path directory) throws IOException { saveMetadata(directory, info(directory)); }

  private BufferedReader reader(Path directory) throws IOException {
    Path raw = directory.resolve("messages.jsonl");
    Path zipped = directory.resolve("messages.jsonl.gz");
    if (Files.exists(raw)) return Files.newBufferedReader(raw);
    if (Files.exists(zipped)) return new BufferedReader(new InputStreamReader(
        new GZIPInputStream(Files.newInputStream(zipped)), StandardCharsets.UTF_8));
    return new BufferedReader(new java.io.StringReader(""));
  }

  private BufferedReader reader(Path directory, int afterTurn) throws IOException {
    Path raw = directory.resolve("messages.jsonl");
    if (afterTurn == 0 || !Files.exists(raw)) return reader(directory);
    long position = Files.size(raw);
    try (var index = Files.newBufferedReader(directory.resolve("history-index.jsonl"))) {
      String line;
      while ((line = index.readLine()) != null) {
        IndexEntry item = mapper.readValue(line, IndexEntry.class);
        if (item.turn() > afterTurn) { position = item.byteOffset(); break; }
      }
    }
    FileChannel channel = FileChannel.open(raw, StandardOpenOption.READ);
    channel.position(position);
    return new BufferedReader(new InputStreamReader(java.nio.channels.Channels.newInputStream(channel), StandardCharsets.UTF_8));
  }

  Page page(Path directory, int offset, int limit, boolean hydrate) throws IOException {
    if (offset < 0 || limit < 1 || limit > 100) throw new ApiRequestException(
        org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid history page");
    Metadata meta = info(directory);
    List<MemoryStore.StoredTurn> turns = new ArrayList<>();
    try (BufferedReader input = reader(directory, offset)) {
      String line;
      while ((line = input.readLine()) != null) {
        if (line.isBlank()) continue;
        MemoryStore.StoredTurn turn = mapper.readValue(line, MemoryStore.StoredTurn.class);
        if (turn.index() <= offset) continue;
        if (turns.size() >= limit) break;
        turns.add(hydrate ? hydrate(directory, turn, 8192) : turn);
      }
    }
    int next = offset + turns.size();
    return new Page(List.copyOf(turns), offset, next < meta.turnCount() ? next : null, meta.turnCount());
  }

  List<MemoryStore.StoredTurn> unsummarized(Path directory) throws IOException {
    info(directory);
    int through = summary(directory).through();
    List<MemoryStore.StoredTurn> turns = new ArrayList<>();
    try (BufferedReader input = reader(directory, through)) {
      String line;
      while ((line = input.readLine()) != null) {
        if (!line.isBlank()) {
          MemoryStore.StoredTurn turn = mapper.readValue(line, MemoryStore.StoredTurn.class);
          if (turn.index() > through) turns.add(turn); // Spilled payloads stay as bounded previews in context.
          if (turns.size() == 100) break;
        }
      }
    }
    return List.copyOf(turns);
  }

  void append(Path directory, MemoryStore.StoredTurn turn, long userBytes) throws IOException {
    Metadata meta = info(directory);
    if (turn.index() != meta.turnCount() + 1) throw new IOException("Stale session write rejected");
    long restoredExtra = 0;
    Path rawTranscript = directory.resolve("messages.jsonl"), archive = directory.resolve("messages.jsonl.gz");
    if (!Files.exists(rawTranscript) && Files.exists(archive)) {
      try (var input = new GZIPInputStream(Files.newInputStream(archive))) {
        byte[] block = new byte[8192]; int count;
        while ((count = input.read(block)) != -1) restoredExtra += count;
      }
      restoredExtra -= Files.size(archive);
    }
    userBytes += Math.max(0, restoredExtra);
    List<JsonNode> output = new ArrayList<>();
    List<Path> created = new ArrayList<>();
    boolean committed = false;
    try {
      long additional = 0;
      for (JsonNode item : turn.output()) {
        String content = item.path("output").asString("");
        if (item.path("type").asString("").equals("function_call_output")
            && content.length() > properties.toolInlineChars()) {
          String ref = UUID.randomUUID() + ".json";
          Path payload = directory.resolve("payloads").resolve(ref);
          additional += content.getBytes(StandardCharsets.UTF_8).length;
          if (userBytes + additional > properties.userQuotaBytes()) quotaExceeded();
          MemoryStore.writeAtomic(payload, content);
          created.add(payload);
          String pointer = mapper.writeValueAsString(Map.of("historical", true, "payloadRef", ref,
              "preview", MemoryStore.excerpt(content, 1000), "note", "Read with search_history near this turn; re-query business tools for current data."));
          output.add(mapper.valueToTree(Map.of("type", "function_call_output",
              "call_id", item.path("call_id").asString(), "output", pointer)));
        } else output.add(item);
      }
      MemoryStore.StoredTurn stored = new MemoryStore.StoredTurn(turn.index(), turn.timestamp(), turn.message(),
          List.copyOf(output), turn.answer(), turn.sources(), turn.completed(), turn.model());
      String line = mapper.writeValueAsString(stored) + "\n";
      long byteOffset = Files.exists(rawTranscript) ? Files.size(rawTranscript)
          : (Files.exists(archive) ? restoredExtra + Files.size(archive) : 0);
      String index = mapper.writeValueAsString(index(hydrate(directory, turn, 64000), byteOffset)) + "\n";
      if (userBytes + additional + line.getBytes(StandardCharsets.UTF_8).length
          + index.getBytes(StandardCharsets.UTF_8).length + 8192 > properties.userQuotaBytes()) quotaExceeded();
      // Keep the gzip until a complete raw copy exists, and check its expanded size against quota first.
      unarchive(directory);
      appendLine(directory.resolve("messages.jsonl"), line);
      committed = true;
      appendLine(directory.resolve("history-index.jsonl"), index);
      saveMetadata(directory, new Metadata(meta.id(), meta.createdAt(), turn.timestamp(),
          meta.turnCount() == 0 ? MemoryStore.excerpt(turn.message(), 70) : meta.title(), turn.index(), false));
      synchronized (reconciled) { reconciled.put(directory, Files.size(directory.resolve("messages.jsonl"))); }
    } finally {
      if (!committed) for (Path payload : created) Files.deleteIfExists(payload);
    }
  }

  static void quotaExceeded() {
    throw new ApiRequestException(org.springframework.http.HttpStatus.INSUFFICIENT_STORAGE,
        "History storage quota reached. Export/delete old conversations or increase agent.history.user-quota-bytes; unexpired history was preserved.");
  }

  Map<String, Object> spill(Path directory, String content, long userBytes) throws IOException {
    if (userBytes + content.getBytes(StandardCharsets.UTF_8).length + 8192 > properties.userQuotaBytes()) quotaExceeded();
    String ref = UUID.randomUUID() + ".json";
    MemoryStore.writeAtomic(directory.resolve("payloads").resolve(ref), content);
    return Map.of("payloadRef", ref, "preview", MemoryStore.excerpt(content, 1000),
        "recordedAt", Instant.now().toString(), "note", "Oversized result saved; read chunks with search_history(sessionId, payloadRef, payloadOffset). Re-query business tools for later current data.");
  }

  Map<String, Object> payload(Path directory, String ref, int offset) throws IOException {
    if (!PAYLOAD.matcher(ref).matches() || offset < 0 || offset > 10_000_000)
      throw new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid payload reference or offset");
    Path raw = directory.resolve("payloads").resolve(ref), zip = directory.resolve("payloads").resolve(ref + ".gz");
    if (!Files.isRegularFile(raw) && !Files.isRegularFile(zip))
      throw new ApiRequestException(org.springframework.http.HttpStatus.NOT_FOUND, "Payload not found for this user's session");
    try (var input = new InputStreamReader(Files.exists(raw) ? Files.newInputStream(raw)
        : new GZIPInputStream(Files.newInputStream(zip)), StandardCharsets.UTF_8)) {
      long skipped = 0;
      while (skipped < offset) {
        long amount = input.skip(offset - skipped);
        if (amount == 0) break; skipped += amount;
      }
      char[] buffer = new char[1001]; int count = 0, n;
      while (count < buffer.length && (n = input.read(buffer, count, buffer.length - count)) != -1) count += n;
      int length = Math.min(1000, count);
      if (length > 0 && Character.isHighSurrogate(buffer[length - 1])) length--;
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("payloadRef", ref); result.put("offset", offset);
      result.put("content", new String(buffer, 0, length)); result.put("historical", true);
      result.put("nextOffset", count > length ? offset + length : null);
      return result;
    }
  }

  private static void appendLine(Path path, String line) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
        StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      ByteBuffer bytes = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
      while (bytes.hasRemaining()) channel.write(bytes);
      channel.force(true);
    }
  }

  private void rebuild(Path directory) throws IOException {
    repairTail(directory.resolve("messages.jsonl"));
    JsonNode old = mapper.readTree(Files.readString(directory.resolve("session.json")));
    String createdAt = old.path("createdAt").asString(Instant.now().toString());
    String title = "New conversation", updatedAt = createdAt;
    int count = 0;
    long byteOffset = 0;
    Path temp = Files.createTempFile(directory, ".index-", ".tmp");
    try {
      try (BufferedReader input = reader(directory); var out = Files.newBufferedWriter(temp)) {
        String line;
        while ((line = input.readLine()) != null) {
          if (line.isBlank()) continue;
          MemoryStore.StoredTurn turn = mapper.readValue(line, MemoryStore.StoredTurn.class);
          if (turn.index() != count + 1) throw new IOException("History turn sequence is corrupt");
          count++;
          if (count == 1) title = MemoryStore.excerpt(turn.message(), 70);
          updatedAt = turn.timestamp();
          out.write(mapper.writeValueAsString(index(hydrate(directory, turn, 64000), byteOffset))); out.newLine();
          byteOffset += line.getBytes(StandardCharsets.UTF_8).length + 1L;
        }
      }
      Files.move(temp, directory.resolve("history-index.jsonl"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      saveMetadata(directory, new Metadata(old.path("id").asString(), createdAt, updatedAt, title, count,
          !Files.exists(directory.resolve("messages.jsonl")) && Files.exists(directory.resolve("messages.jsonl.gz"))));
    } finally { Files.deleteIfExists(temp); }
  }

  /** A newline is the commit marker. Recover only a torn final append, never skip a corrupt middle row. */
  private static void repairTail(Path path) throws IOException {
    if (!Files.exists(path)) return;
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      long end = channel.size();
      if (end == 0) return;
      ByteBuffer byteBuffer = ByteBuffer.allocate(1);
      channel.read(byteBuffer, end - 1);
      if (byteBuffer.array()[0] == '\n') return;
      long position = end;
      ByteBuffer block = ByteBuffer.allocate(4096);
      while (position > 0) {
        int size = (int) Math.min(block.capacity(), position); position -= size;
        block.clear().limit(size);
        channel.read(block, position);
        for (int i = size - 1; i >= 0; i--) {
          if (block.array()[i] == '\n') { channel.truncate(position + i + 1); channel.force(true); return; }
        }
      }
      channel.truncate(0); channel.force(true);
    }
  }

  private MemoryStore.StoredTurn hydrate(Path directory, MemoryStore.StoredTurn turn, int maxChars) throws IOException {
    List<JsonNode> output = new ArrayList<>();
    for (JsonNode item : turn.output()) {
      String text = item.path("output").asString("");
      if (item.path("type").asString("").equals("function_call_output")) {
        JsonNode pointer;
        try { pointer = mapper.readTree(text); } catch (RuntimeException error) { pointer = null; }
        if (pointer != null && pointer.has("payloadRef")) {
          String ref = pointer.path("payloadRef").asString();
          if (!PAYLOAD.matcher(ref).matches()) throw new IOException("Invalid historical payload reference");
          try (var input = payloadReader(directory, ref)) {
            char[] buffer = new char[maxChars + 1]; int count = 0, n;
            while (count < buffer.length && (n = input.read(buffer, count, buffer.length - count)) != -1) count += n;
            text = new String(buffer, 0, Math.min(maxChars, count));
            if (count > maxChars) text += "… [saved payload " + ref + "; read chunks with search_history]";
          }
          item = mapper.valueToTree(Map.of("type", "function_call_output", "call_id",
              item.path("call_id").asString(), "output", text));
        }
      }
      output.add(item);
    }
    return new MemoryStore.StoredTurn(turn.index(), turn.timestamp(), turn.message(), List.copyOf(output),
        turn.answer(), turn.sources(), turn.completed(), turn.model());
  }

  private static java.io.Reader payloadReader(Path directory, String ref) throws IOException {
    if (!PAYLOAD.matcher(ref).matches()) throw new IOException("Invalid historical payload reference");
    Path raw = directory.resolve("payloads").resolve(ref), zip = directory.resolve("payloads").resolve(ref + ".gz");
    if (Files.exists(raw)) return Files.newBufferedReader(raw);
    if (Files.exists(zip)) return new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(zip)), StandardCharsets.UTF_8));
    throw new IOException("Missing historical payload");
  }

  private static List<String> terms(String text) {
    Set<String> words = new LinkedHashSet<>();
    var matches = WORD.matcher(text.toLowerCase(java.util.Locale.ROOT));
    while (matches.find() && words.size() < 512) {
      String word = matches.group(); words.add(MemoryStore.excerpt(word, 80));
      // CJK does not have whitespace word boundaries: use bigrams for keyword discovery.
      if (word.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
        int[] points = word.codePoints().toArray();
        for (int i = 0; i + 1 < points.length && words.size() < 512; i++) words.add(new String(points, i, 2));
      }
    }
    return List.copyOf(words);
  }

  private IndexEntry index(MemoryStore.StoredTurn turn, long offset) {
    String text = turn.message() + "\n" + turn.answer() + "\n" + turn.output().stream()
        .filter(item -> !item.path("type").asString("").equals("reasoning")).toList();
    return new IndexEntry(turn.index(), turn.timestamp(), MemoryStore.excerpt(turn.message() + "\n" + turn.answer(), 600), terms(text), offset);
  }

  SearchPage search(List<Path> directories, String query, int offset, int limit) throws IOException {
    if (query == null || query.isBlank() || query.length() > 2000 || offset < 0 || offset > 10000
        || limit < 1 || limit > 20) throw new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid history search page");
    List<String> needles = terms(query);
    if (needles.isEmpty()) throw new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST, "Use searchable words or resource IDs");
    record Hit(Path directory, Metadata metadata, IndexEntry entry, int score) {}
    Comparator<Hit> rank = Comparator.comparingInt(Hit::score).reversed()
        .thenComparing(hit -> hit.entry().timestamp(), Comparator.reverseOrder())
        .thenComparing(hit -> hit.metadata().id()).thenComparingInt(hit -> hit.entry().turn());
    PriorityQueue<Hit> best = new PriorityQueue<>(rank.reversed());
    int total = 0;
    for (Path directory : directories) {
      Metadata meta = info(directory);
      try (var input = Files.newBufferedReader(directory.resolve("history-index.jsonl"))) {
        String line;
        while ((line = input.readLine()) != null) {
          IndexEntry entry = mapper.readValue(line, IndexEntry.class);
          int score = 0;
          String preview = entry.excerpt().toLowerCase(java.util.Locale.ROOT);
          for (String needle : needles) if (entry.keywords().contains(needle)) score += preview.contains(needle) ? 3 : 1;
          if (preview.contains(query.toLowerCase(java.util.Locale.ROOT))) score += 5;
          if (score == 0) continue;
          total++; best.add(new Hit(directory, meta, entry, score));
          if (best.size() > offset + limit) best.poll();
        }
      }
    }
    List<Hit> sorted = best.stream().sorted(rank).toList();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Hit hit : sorted.subList(Math.min(offset, sorted.size()), sorted.size())) {
      result.add(Map.of("sessionId", hit.metadata().id(), "title", hit.metadata().title(),
          "turn", hit.entry().turn(), "timestamp", hit.entry().timestamp(), "score", hit.score(),
          "excerpt", hit.entry().excerpt(), "archived", hit.metadata().archived(), "historical", true));
    }
    return new SearchPage(List.copyOf(result), offset, offset + result.size() < total ? offset + result.size() : null, total);
  }

  void invalidate(Path directory) { synchronized (reconciled) { reconciled.remove(directory); } }

  private void unarchive(Path directory) throws IOException {
    Path raw = directory.resolve("messages.jsonl"), zip = directory.resolve("messages.jsonl.gz");
    if (Files.exists(raw) || !Files.exists(zip)) return;
    Path temp = Files.createTempFile(directory, ".restore-", ".tmp");
    try {
      try (var input = new GZIPInputStream(Files.newInputStream(zip))) { Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING); }
      Files.move(temp, raw, StandardCopyOption.ATOMIC_MOVE);
      Files.delete(zip);
      synchronized (reconciled) { reconciled.remove(directory); }
    } finally { Files.deleteIfExists(temp); }
  }

  static long diskBytes(Path directory) throws IOException {
    if (!Files.exists(directory)) return 0;
    long total = 0;
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) total += Files.size(path);
    }
    return total;
  }

  static void deleteTree(Path directory) throws IOException {
    // Callers supply a validated session directory; do not follow links into other directories.
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }

  void export(Path directory, java.io.OutputStream output) throws IOException {
    info(directory);
    try (var input = reader(directory)) {
      String line;
      while ((line = input.readLine()) != null) {
        if (line.isBlank()) continue;
        var turn = mapper.readValue(line, MemoryStore.StoredTurn.class);
        JsonNode node = mapper.valueToTree(turn);
        output.write('{'); boolean firstField = true;
        for (var field : node.properties()) {
          if (!firstField) output.write(','); firstField = false;
          output.write((mapper.writeValueAsString(field.getKey()) + ":").getBytes(StandardCharsets.UTF_8));
          if (!field.getKey().equals("output")) { output.write(mapper.writeValueAsBytes(field.getValue())); continue; }
          output.write('['); boolean firstItem = true;
          for (JsonNode item : field.getValue()) {
            if (!firstItem) output.write(','); firstItem = false;
            JsonNode pointer = null;
            if (item.path("type").asString("").equals("function_call_output")) {
              try { pointer = mapper.readTree(item.path("output").asString()); } catch (RuntimeException ignored) { /* Plain text output. */ }
            }
            if (pointer == null || !pointer.has("payloadRef")) { output.write(mapper.writeValueAsBytes(item)); continue; }
            String prefix = "{\"type\":\"function_call_output\",\"call_id\":"
                + mapper.writeValueAsString(item.path("call_id").asString()) + ",\"output\":\"";
            output.write(prefix.getBytes(StandardCharsets.UTF_8));
            try (var payload = payloadReader(directory, pointer.path("payloadRef").asString())) {
              char[] block = new char[8192]; int n;
              while ((n = payload.read(block)) != -1) {
                String part = new String(block, 0, n);
                if (Character.isHighSurrogate(block[n - 1])) {
                  int next = payload.read(); if (next != -1) part += (char) next;
                }
                String escaped = mapper.writeValueAsString(part);
                output.write(escaped.substring(1, escaped.length() - 1).getBytes(StandardCharsets.UTF_8));
              }
            }
            output.write("\"}".getBytes(StandardCharsets.UTF_8));
          }
          output.write(']');
        }
        output.write("}\n".getBytes(StandardCharsets.UTF_8));
      }
    }
  }
}
