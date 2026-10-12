package com.example.ragbot;

import java.io.IOException;

@FunctionalInterface
public interface AgentListener {
  void on(AgentEvent event) throws IOException;
}
