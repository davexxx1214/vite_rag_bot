package com.example.ssbotcli;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class ServiceCatalog {
  public record LogSpec(Path directory, String pattern) {}
  public record Service(String id, String type, String serviceName, String siteName, String appPool,
      Path installDir, List<LogSpec> logs, List<Integer> ports, URI health, List<URI> dependencies,
      List<String> eventProviders) {}
  private final Map<String, Service> entries;
  private final Path source;
  private ServiceCatalog(Path source, Map<String, Service> entries) {
    this.source = source; this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
  }
  public Path source() { return source; }
  public List<Service> services() { return List.copyOf(entries.values()); }
  public Service require(String id) {
    Service s = entries.get(id.startsWith("@") ? id.substring(1) : id);
    if (s == null) throw new IllegalArgumentException("Unknown registered service; use list_services.");
    return s;
  }
  public static ServiceCatalog load(Path path, boolean required) throws IOException {
    Path source = path.toAbsolutePath().normalize();
    if (!Files.exists(source)) {
      if (required) throw new IOException("Services file not found: " + source);
      return new ServiceCatalog(source, Map.of());
    }
    if (Files.size(source) > 262144) throw new IllegalArgumentException("Services file exceeds 256 KiB.");
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(12); options.setCodePointLimit(262144);
    Object loaded;
    try (var reader = Files.newBufferedReader(source)) { loaded = new Yaml(new SafeConstructor(options)).load(reader); }
    catch (org.yaml.snakeyaml.error.YAMLException e) {
      throw new IllegalArgumentException("Invalid services YAML; check syntax, duplicate keys and unsupported tags.");
    }
    Map<?, ?> root = map(loaded); keys(root, Set.of("services"));
    Map<String, Service> entries = new LinkedHashMap<>();
    for (Object raw : list(root.get("services"), 128)) {
      Map<?, ?> m = map(raw);
      keys(m, Set.of("id", "type", "service_name", "site_name", "app_pool", "install_dir", "logs", "ports", "health", "depends_on", "event_providers"));
      String id = text(m, "id", true), type = text(m, "type", true);
      if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid service id.");
      if (!Set.of("windows-service", "iis-site").contains(type)) throw new IllegalArgumentException("Unsupported service type.");
      String name = text(m, "service_name", type.equals("windows-service"));
      String site = text(m, "site_name", type.equals("iis-site")), pool = text(m, "app_pool", false);
      if (!name.isEmpty() && !name.matches("[A-Za-z0-9_. -]{1,256}")) throw new IllegalArgumentException("Invalid Windows service name.");
      String dir = text(m, "install_dir", false);
      List<LogSpec> logs = new ArrayList<>();
      for (String log : strings(m.get("logs"))) {
        String normalized = log.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String parent = slash < 0 ? "." : normalized.substring(0, slash + 1);
        String pattern = normalized.substring(slash + 1);
        if (parent.matches(".*[?*].*") || !pattern.matches("[^/\\\\:{}\\[\\]]{1,256}") || pattern.equals(".."))
          throw new IllegalArgumentException("Log wildcards (* or ?) allowed only in the filename.");
        logs.add(new LogSpec(resolve(source, parent), pattern));
      }
      List<Integer> ports = new ArrayList<>();
      for (Object port : list(m.get("ports"), 32)) {
        if (!(port instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 1 || n.intValue() > 65535)
          throw new IllegalArgumentException("Invalid service port.");
        ports.add(((Number) port).intValue());
      }
      String health = text(m, "health", false);
      if (strings(m.get("depends_on")).size() > 8) throw new IllegalArgumentException("At most eight dependencies per service.");
      Service service = new Service(id, type, name, site, pool, dir.isEmpty() ? null : resolve(source, dir),
          List.copyOf(logs), List.copyOf(ports), health.isEmpty() ? null : endpoint(health, false),
          strings(m.get("depends_on")).stream().map(v -> endpoint(v, true)).toList(), strings(m.get("event_providers")));
      if (entries.putIfAbsent(id, service) != null) throw new IllegalArgumentException("Duplicate service id: " + id);
    }
    return new ServiceCatalog(source, entries);
  }
  private static Path resolve(Path source, String text) {
    if (text.startsWith("\\\\") || text.startsWith("//") || text.contains("%"))
      throw new IllegalArgumentException("Use explicit local paths (no UNC or environment expansion).");
    return source.getParent().resolve(text).normalize().toAbsolutePath();
  }
  private static URI endpoint(String value, boolean tcp) {
    URI u = URI.create(value);
    if (u.getHost() == null || u.getUserInfo() != null || u.getFragment() != null || u.getQuery() != null
        || !Set.of("http", "https", "tcp").contains(u.getScheme()) || (!tcp && u.getScheme().equals("tcp"))
        || u.getPort() > 65535 || u.getPort() == 0 || (u.getScheme().equals("tcp") && (u.getPort() < 1 || !u.getPath().isEmpty())))
      throw new IllegalArgumentException("Invalid endpoint: use HTTP(S) or dependency TCP with a port, without credentials or query strings.");
    return u;
  }
  private static Map<?, ?> map(Object o) {
    if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("Expected a YAML mapping.");
    return m;
  }
  private static void keys(Map<?, ?> m, Set<String> allowed) {
    if (m.keySet().stream().anyMatch(k -> !allowed.contains(k))) throw new IllegalArgumentException("Unknown services YAML field.");
  }
  private static List<?> list(Object o, int max) {
    if (o == null) return List.of();
    if (!(o instanceof List<?> l) || l.size() > max) throw new IllegalArgumentException("Invalid or oversized YAML list.");
    return l;
  }
  private static List<String> strings(Object o) {
    return list(o, 32).stream().map(v -> {
      if (!(v instanceof String s) || s.isBlank() || s.length() > 2048 || s.chars().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("Invalid YAML string.");
      return s;
    }).toList();
  }
  private static String text(Map<?, ?> m, String key, boolean required) {
    Object value = m.get(key);
    if (value == null && !required) return "";
    if (!(value instanceof String s) || s.isBlank() || s.length() > 2048 || s.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid or missing " + key);
    return s;
  }
}
