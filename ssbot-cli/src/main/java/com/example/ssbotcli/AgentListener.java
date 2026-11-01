package com.example.ssbotcli;

import java.io.IOException;
import java.util.Map;

@FunctionalInterface
public interface AgentListener {
  void on(String type, Map<String, Object> data) throws IOException;
}
