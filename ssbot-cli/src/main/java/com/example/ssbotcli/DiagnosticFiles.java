package com.example.ssbotcli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

final class DiagnosticFiles {
  static String redact(String text) {
    return text.replaceAll("(?i)(authorization\\s*[:=]\\s*(?:bearer|basic)\\s+)[^\\s\"']+", "$1[REDACTED]")
        .replaceAll("(?i)((?:password|passwd|pwd|api[_-]?key|access[_-]?token|client[_-]?secret|token)\\s*[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s\"';,}]+)", "$1[REDACTED]")
        .replaceAll("(://)[^\\s/@:]+:[^\\s/@]+@", "$1[REDACTED]@");
  }
  static Map<String, Object> deployment(Path directory, RunControl control) throws IOException {
    if (directory == null) return Map.of("configured", false);
    var directoryAttributes = Files.readAttributes(directory, BasicFileAttributes.class);
    if (!directoryAttributes.isDirectory()) return Map.of("path", directory.toString(), "exists", true, "error", "Deployment path is not a directory");
    List<Map<String, Object>> files = new ArrayList<>();
    boolean truncated = false;
    try (var entries = Files.newDirectoryStream(directory)) {
      for (Path file : entries) {
        control.check();
        if (files.size() >= 100) { truncated = true; break; }
        var attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        files.add(Map.of("name", file.getFileName().toString(), "bytes", attrs.size(),
            "modified", attrs.lastModifiedTime().toString(), "directory", attrs.isDirectory(), "link", attrs.isSymbolicLink()));
      }
    }
    var store = Files.getFileStore(directory);
    return Map.of("path", directory.toString(), "exists", true, "files", files, "truncated", truncated,
        "usable_bytes", store.getUsableSpace(), "total_bytes", store.getTotalSpace());
  }
  static Map<String, Object> tail(ServiceCatalog.LogSpec spec, int lines, String encoding, RunControl control) throws IOException {
    Path directory = spec.directory().toRealPath();
    Pattern matcher = Pattern.compile("(?i)" + Pattern.quote(spec.pattern()).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q"));
    Path newest = null; long newestTime = Long.MIN_VALUE; int scanned = 0; boolean scanTruncated = false;
    try (var files = Files.newDirectoryStream(directory)) {
      for (Path file : files) {
        control.check();
        if (++scanned > 10000) { scanTruncated = true; break; }
        if (!matcher.matcher(file.getFileName().toString()).matches() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
        long time = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
        if (newest == null || time > newestTime) { newest = file; newestTime = time; }
      }
    }
    if (newest == null) return Map.of("error", "No matching regular log file", "scan_truncated", scanTruncated);
    if (!newest.toRealPath().getParent().equals(directory)) throw new IOException("Log path escaped the registered directory");
    byte[] bytes; long start, size;
    try (SeekableByteChannel channel = Files.newByteChannel(newest, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      size = channel.size(); start = Math.max(0, size - 65536);
      channel.position(start); ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
      while (buffer.hasRemaining() && channel.read(buffer) >= 0) control.check();
      bytes = Arrays.copyOf(buffer.array(), buffer.position());
    }
    Charset charset = switch (encoding) {
      case "utf-8" -> StandardCharsets.UTF_8;
      case "gb18030" -> Charset.forName("GB18030");
      case "utf-16le" -> StandardCharsets.UTF_16LE;
      default -> throw new IllegalArgumentException("Use utf-8, gb18030 or utf-16le");
    };
    if (encoding.equals("utf-16le") && start % 2 != 0) { bytes = Arrays.copyOfRange(bytes, 1, bytes.length); }
    String text = new String(bytes, charset);
    if (start > 0) { int lf = text.indexOf('\n'); text = lf < 0 ? "" : text.substring(lf + 1); }
    List<String> all = text.lines().toList();
    int first = Math.max(0, all.size() - lines);
    return Map.of("file", newest.toString(), "encoding", encoding, "file_bytes", size,
        "byte_offset", start, "truncated", start > 0 || first > 0, "scan_truncated", scanTruncated,
        "lines", all.subList(first, all.size()).stream().map(DiagnosticFiles::redact).toList());
  }
}
