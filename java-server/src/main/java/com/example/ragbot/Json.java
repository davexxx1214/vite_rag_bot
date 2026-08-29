package com.example.ragbot;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
  private Json() {}

  static Object parse(String source) {
    return new Parser(source).parse();
  }

  static String stringify(Object value) {
    StringBuilder output = new StringBuilder();
    write(value, output);
    return output.toString();
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected JSON object");
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  static List<Object> array(Object value) {
    if (!(value instanceof List<?>)) throw new IllegalArgumentException("Expected JSON array");
    return (List<Object>) value;
  }

  static String string(Object value) {
    if (!(value instanceof String text)) throw new IllegalArgumentException("Expected JSON string");
    return text;
  }

  static double number(Object value) {
    if (!(value instanceof Number number)) throw new IllegalArgumentException("Expected JSON number");
    return number.doubleValue();
  }

  private static void write(Object value, StringBuilder output) {
    if (value == null) {
      output.append("null");
    } else if (value instanceof String text) {
      writeString(text, output);
    } else if (value instanceof Boolean) {
      output.append(value);
    } else if (value instanceof Number number) {
      double numeric = number.doubleValue();
      output.append(Double.isFinite(numeric) ? number : "null");
    } else if (value instanceof Map<?, ?> map) {
      output.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) output.append(',');
        first = false;
        writeString(String.valueOf(entry.getKey()), output);
        output.append(':');
        write(entry.getValue(), output);
      }
      output.append('}');
    } else if (value instanceof Iterable<?> iterable) {
      output.append('[');
      boolean first = true;
      for (Object item : iterable) {
        if (!first) output.append(',');
        first = false;
        write(item, output);
      }
      output.append(']');
    } else if (value.getClass().isArray()) {
      output.append('[');
      for (int index = 0; index < Array.getLength(value); index++) {
        if (index > 0) output.append(',');
        write(Array.get(value, index), output);
      }
      output.append(']');
    } else {
      throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
    }
  }

  private static void writeString(String value, StringBuilder output) {
    output.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> output.append("\\\"");
        case '\\' -> output.append("\\\\");
        case '\b' -> output.append("\\b");
        case '\f' -> output.append("\\f");
        case '\n' -> output.append("\\n");
        case '\r' -> output.append("\\r");
        case '\t' -> output.append("\\t");
        default -> {
          if (character < 0x20) output.append(String.format("\\u%04x", (int) character));
          else output.append(character);
        }
      }
    }
    output.append('"');
  }

  private static final class Parser {
    private final String source;
    private int cursor;

    private Parser(String source) {
      this.source = source;
    }

    private Object parse() {
      skipWhitespace();
      Object value = parseValue();
      skipWhitespace();
      if (cursor != source.length()) fail("Unexpected trailing content");
      return value;
    }

    private Object parseValue() {
      skipWhitespace();
      if (cursor >= source.length()) return fail("Unexpected end of input");
      return switch (source.charAt(cursor)) {
        case '{' -> parseObject();
        case '[' -> parseArray();
        case '"' -> parseString();
        case 't' -> parseLiteral("true", Boolean.TRUE);
        case 'f' -> parseLiteral("false", Boolean.FALSE);
        case 'n' -> parseLiteral("null", null);
        default -> parseNumber();
      };
    }

    private Map<String, Object> parseObject() {
      cursor++;
      Map<String, Object> result = new LinkedHashMap<>();
      skipWhitespace();
      if (consume('}')) return result;
      while (true) {
        skipWhitespace();
        if (cursor >= source.length() || source.charAt(cursor) != '"') fail("Expected object key");
        String key = parseString();
        skipWhitespace();
        expect(':');
        result.put(key, parseValue());
        skipWhitespace();
        if (consume('}')) return result;
        expect(',');
      }
    }

    private List<Object> parseArray() {
      cursor++;
      List<Object> result = new ArrayList<>();
      skipWhitespace();
      if (consume(']')) return result;
      while (true) {
        result.add(parseValue());
        skipWhitespace();
        if (consume(']')) return result;
        expect(',');
      }
    }

    private String parseString() {
      expect('"');
      StringBuilder result = new StringBuilder();
      while (cursor < source.length()) {
        char character = source.charAt(cursor++);
        if (character == '"') return result.toString();
        if (character != '\\') {
          if (character < 0x20) fail("Unescaped control character");
          result.append(character);
          continue;
        }
        if (cursor >= source.length()) fail("Unterminated escape sequence");
        char escaped = source.charAt(cursor++);
        switch (escaped) {
          case '"', '\\', '/' -> result.append(escaped);
          case 'b' -> result.append('\b');
          case 'f' -> result.append('\f');
          case 'n' -> result.append('\n');
          case 'r' -> result.append('\r');
          case 't' -> result.append('\t');
          case 'u' -> {
            if (cursor + 4 > source.length()) fail("Invalid unicode escape");
            try {
              result.append((char) Integer.parseInt(source.substring(cursor, cursor + 4), 16));
            } catch (NumberFormatException error) {
              fail("Invalid unicode escape");
            }
            cursor += 4;
          }
          default -> fail("Invalid escape sequence");
        }
      }
      return fail("Unterminated string");
    }

    private Object parseNumber() {
      int start = cursor;
      if (consume('-')) {}
      consumeDigits();
      boolean decimal = false;
      if (consume('.')) {
        decimal = true;
        consumeDigits();
      }
      if (cursor < source.length() && (source.charAt(cursor) == 'e' || source.charAt(cursor) == 'E')) {
        decimal = true;
        cursor++;
        if (cursor < source.length() && (source.charAt(cursor) == '+' || source.charAt(cursor) == '-')) cursor++;
        consumeDigits();
      }
      if (start == cursor) return fail("Expected value");
      String token = source.substring(start, cursor);
      try {
        return decimal ? Double.parseDouble(token) : Long.parseLong(token);
      } catch (NumberFormatException error) {
        return fail("Invalid number");
      }
    }

    private void consumeDigits() {
      int start = cursor;
      while (cursor < source.length() && Character.isDigit(source.charAt(cursor))) cursor++;
      if (start == cursor) fail("Expected digit");
    }

    private Object parseLiteral(String literal, Object value) {
      if (!source.startsWith(literal, cursor)) return fail("Invalid literal");
      cursor += literal.length();
      return value;
    }

    private void skipWhitespace() {
      while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++;
    }

    private boolean consume(char expected) {
      if (cursor < source.length() && source.charAt(cursor) == expected) {
        cursor++;
        return true;
      }
      return false;
    }

    private void expect(char expected) {
      if (!consume(expected)) fail("Expected '" + expected + "'");
    }

    private <T> T fail(String message) {
      throw new IllegalArgumentException(message + " at character " + cursor);
    }
  }
}
