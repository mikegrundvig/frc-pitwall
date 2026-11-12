package com.michaelgrundvig.frc.pitwall;

import com.sun.net.httpserver.HttpExchange;
import io.avaje.json.JsonException;
import io.avaje.jsonb.JsonType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a request says: its body as a JSON object, a file's bytes, and its query's parameters, each
 * refused as a {@link Problem} when it isn't what the page sends.
 */
final class Requests {
  /** The page's requests are a few hundred bytes; anything much bigger isn't from it. */
  static final int MAX_BODY_BYTES = 64 * 1024;

  /** How deeply a request's JSON may nest: the page sends flat objects. */
  private static final int MAX_NESTING = 8;

  /** The longest query the page sends, and then some. */
  private static final int MAX_QUERY_CHARACTERS = 2048;

  private final JsonType<Object> json;

  /**
   * @param json reads the requests' JSON
   */
  Requests(JsonType<Object> json) {
    this.json = json;
  }

  /** The request's body as a JSON object, or the problem with it. */
  Map<String, @Nullable Object> object(HttpExchange exchange) throws IOException {
    String type = exchange.getRequestHeaders().getFirst("Content-Type");
    if (type == null || !isJson(type)) {
      throw new Problem(
          415,
          "Unsupported media type",
          "Send a JSON merge patch, with Content-Type: application/merge-patch+json");
    }
    byte[] body = bytes(exchange, "The page's requests are well under 64 KB");
    if (nesting(body) > MAX_NESTING) {
      // Checked before parsing: the parser recurses, and a deep enough body overflows its stack.
      throw new Problem(400, "Too deeply nested", "The page's requests are flat JSON objects");
    }
    Object parsed;
    try {
      parsed = json.fromJson(new String(body, StandardCharsets.UTF_8));
    } catch (JsonException e) {
      throw new Problem(400, "Not JSON", Said.of(e));
    }
    if (!(parsed instanceof Map<?, ?> map)) {
      throw new Problem(400, "Not an object", "A merge patch is a JSON object");
    }
    Map<String, @Nullable Object> object = new LinkedHashMap<>();
    map.forEach((key, value) -> object.put(String.valueOf(key), value));
    return object;
  }

  private static boolean isJson(String type) {
    return type.startsWith("application/merge-patch+json") || type.startsWith("application/json");
  }

  /**
   * The request's body as it is, {@link #MAX_BODY_BYTES} at most: 413 with the reason given when
   * it's larger.
   */
  static byte[] bytes(HttpExchange exchange, String tooLarge) throws IOException {
    byte[] body;
    try (InputStream in = exchange.getRequestBody()) {
      body = in.readNBytes(MAX_BODY_BYTES + 1);
    }
    if (body.length > MAX_BODY_BYTES) {
      throw new Problem(413, "Too large", tooLarge);
    }
    return body;
  }

  /** How deeply a JSON body's objects and arrays nest, not counting brackets inside strings. */
  private static int nesting(byte[] body) {
    int depth = 0;
    int deepest = 0;
    boolean inString = false;
    boolean escaped = false;
    for (byte b : body) {
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (b == '\\') {
          escaped = true;
        } else if (b == '"') {
          inString = false;
        }
      } else if (b == '"') {
        inString = true;
      } else if (b == '{' || b == '[') {
        deepest = Math.max(deepest, ++depth);
      } else if (b == '}' || b == ']') {
        depth--;
      }
    }
    return deepest;
  }

  /** The request's query parameters, decoded; the last of a name given twice. */
  static Map<String, String> query(HttpExchange exchange) {
    return query(exchange.getRequestURI().getRawQuery());
  }

  /** A query string's parameters, decoded; the last of a name given twice. */
  static Map<String, String> query(@Nullable String raw) {
    Map<String, String> parameters = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) {
      return parameters;
    }
    if (raw.length() > MAX_QUERY_CHARACTERS) {
      throw new Problem(400, "Too long", "A query is at most 2048 characters");
    }
    for (String pair : raw.split("&")) {
      int equals = pair.indexOf('=');
      String key = equals < 0 ? pair : pair.substring(0, equals);
      String value = equals < 0 ? "" : pair.substring(equals + 1);
      try {
        parameters.put(
            URLDecoder.decode(key, StandardCharsets.UTF_8),
            URLDecoder.decode(value, StandardCharsets.UTF_8));
      } catch (IllegalArgumentException e) {
        throw new Problem(400, "Not a query", "The query can't be read: " + Said.of(e));
      }
    }
    return parameters;
  }

  /** A query's parameter as a whole number, or 400 saying it isn't one. */
  static long wholeNumber(String name, @Nullable String text) {
    try {
      return Long.parseLong(String.valueOf(text));
    } catch (NumberFormatException e) {
      throw new Problem(400, "Not a number", name + " is a whole number, not " + text);
    }
  }

  /** A body's value as true or false, or 400 saying it isn't. */
  static boolean bool(String name, @Nullable Object value) {
    if (!(value instanceof Boolean bool)) {
      throw new Problem(400, "Not true or false", name + " must be true or false");
    }
    return bool;
  }

  /**
   * The event stream a request names in {@link PitwallServer#STREAM}, or null when it names none.
   */
  static @Nullable String stream(HttpExchange exchange) {
    String stream = exchange.getRequestHeaders().getFirst(PitwallServer.STREAM);
    if (stream == null || stream.isBlank()) {
      return null;
    }
    return stream;
  }
}
