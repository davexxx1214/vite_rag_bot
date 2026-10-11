package com.example.ragbot;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Declarative read-only HTTP tools. URLs and user identity remain server-controlled. */
@Component
@ConditionalOnProperty(name = "rag.build-index", havingValue = "false", matchIfMissing = true)
public final class BusinessTools {
  public record Parameter(String type, String description, boolean optional, String query) {}
  public record Definition(String name, String description, String title, String status,
      String method, String path, Map<String, Parameter> parameters) {
    public Definition {
      parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
      if (name == null || !name.matches("[a-z][a-z0-9_]{1,63}") || description == null || description.isBlank()
          || title == null || title.isBlank() || status == null || status.isBlank()
          || !"GET".equals(method) || path == null || !path.matches("/[A-Za-z0-9_/{}/-]+"))
        throw new IllegalArgumentException("Invalid business tool definition: " + name);
      for (var field : parameters.entrySet()) {
        String key = field.getKey(); Parameter value = field.getValue();
        if (!key.matches("[a-zA-Z][a-zA-Z0-9_]{0,47}") || List.of("username", "userid", "user_id").contains(key.toLowerCase(java.util.Locale.ROOT))
            || value.description() == null || !List.of("string", "integer", "boolean").contains(value.type()))
          throw new IllegalArgumentException("Invalid business tool parameter: " + key);
        if (value.query() != null && (!value.query().matches("[a-zA-Z][a-zA-Z0-9_-]{0,47}")
            || List.of("username", "userid", "user_id").contains(value.query().toLowerCase(java.util.Locale.ROOT))))
          throw new IllegalArgumentException("Business tools cannot configure identity parameters");
        if (path.contains("{" + key + "}") && (value.optional() || !value.type().equals("string") || value.query() != null))
          throw new IllegalArgumentException("Path parameters must be required strings");
        if (!path.contains("{" + key + "}") && value.query() == null)
          throw new IllegalArgumentException("Unused business tool parameter: " + key);
      }
      var placeholders = Pattern.compile("\\{([^{}]+)}").matcher(path);
      while (placeholders.find()) if (!parameters.containsKey(placeholders.group(1)))
        throw new IllegalArgumentException("Undefined path parameter in " + name);
      if (path.replaceAll("\\{[a-zA-Z][a-zA-Z0-9_]*}", "").matches(".*[{}].*"))
        throw new IllegalArgumentException("Invalid path template in " + name);
    }

    Map<String, Object> fields() {
      Map<String, Object> fields = new LinkedHashMap<>();
      parameters.forEach((key, value) -> fields.put(key, Map.of("type", value.optional()
          ? List.of(value.type(), "null") : value.type(), "description", value.description())));
      return fields;
    }

    String resolvedPath(JsonNode args) {
      String resolved = path;
      for (String key : parameters.keySet()) if (resolved.contains("{" + key + "}"))
        resolved = resolved.replace("{" + key + "}", FinanceApiClient.segment(args.path(key).asString()));
      return resolved;
    }

    Map<String, String> query(JsonNode args) {
      Map<String, String> query = new LinkedHashMap<>();
      parameters.forEach((key, value) -> {
        if (value.query() != null && args.hasNonNull(key)) query.put(value.query(), args.path(key).asString());
      });
      return query;
    }
  }

  private final List<Definition> definitions;

  public BusinessTools(@Value("${agent.tools-config:optional:file:./tools.yaml}") String location) throws IOException {
    boolean optional = location.startsWith("optional:");
    var resource = new DefaultResourceLoader().getResource(optional ? location.substring(9) : location);
    if (!resource.exists() && optional) resource = new ClassPathResource("tools.yaml");
    if (!resource.exists()) throw new IOException("Business tools configuration not found: " + location);
    var yaml = new YamlPropertiesFactoryBean(); yaml.setResources(resource);
    var values = yaml.getObject();
    if (values == null) throw new IOException("Empty business tools configuration");
    definitions = new Binder(new MapConfigurationPropertySource(values))
        .bind("tools", Bindable.listOf(Definition.class)).orElseThrow(() -> new IllegalArgumentException("Missing tools list"));
    if (definitions.size() > 64 || definitions.stream().map(Definition::name).distinct().count() != definitions.size())
      throw new IllegalArgumentException("Too many or duplicate business tools");
  }

  public List<Definition> definitions() { return definitions; }
}
