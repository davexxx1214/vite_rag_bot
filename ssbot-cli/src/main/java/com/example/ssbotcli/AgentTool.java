package com.example.ssbotcli;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Tools are independent of user-domain objects, Spring and terminal rendering. */
public interface AgentTool {
  String name();
  String description();
  Map<String, Object> parameters();
  JsonNode execute(JsonNode arguments, RunControl control) throws Exception;
}
