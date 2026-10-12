package com.example.ragbot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

/** Editable server-owned instructions, reloaded for subsequent model requests. */
@Component
@ConditionalOnProperty(name = "rag.build-index", havingValue = "false", matchIfMissing = true)
public final class SoulInstructions {
  private final String location;

  public SoulInstructions(@Value("${agent.soul-config:optional:file:./SOUL.md}") String location) throws IOException {
    this.location = location;
    read();
  }

  public String read() throws IOException {
    boolean optional = location.startsWith("optional:");
    var resource = new DefaultResourceLoader().getResource(optional ? location.substring(9) : location);
    if (!resource.exists() && optional) resource = new ClassPathResource("SOUL.md");
    if (!resource.exists()) throw new IOException("Soul instructions not found: " + location);
    String content = resource.getContentAsString(StandardCharsets.UTF_8).strip();
    if (content.isBlank()) throw new IOException("Empty soul instructions: " + location);
    return content;
  }
}
