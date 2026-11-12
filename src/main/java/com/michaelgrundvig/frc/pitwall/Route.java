package com.michaelgrundvig.frc.pitwall;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One entry of the server's route table ({@link Api}): a method, the paths it answers, and what
 * answers them. A path is a pattern, matched whole; a plain path is a pattern that matches only
 * itself.
 *
 * @param method the request's method
 * @param path the paths it answers
 * @param handler what answers them
 */
record Route(Method method, Pattern path, Handler handler) {
  /** The methods the API answers. */
  enum Method {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE
  }

  /** Answers a request whose path the route matched, with the match's groups. */
  @FunctionalInterface
  interface Handler {
    void handle(HttpExchange exchange, Matcher path) throws IOException;
  }

  /** Answers a request at a plain path, which has no groups to read. */
  @FunctionalInterface
  interface Plain {
    void handle(HttpExchange exchange) throws IOException;
  }

  /** A route at one plain path. */
  static Route at(Method method, String path, Plain handler) {
    return new Route(
        method,
        Pattern.compile(Pattern.quote(path)),
        (exchange, matched) -> {
          handler.handle(exchange);
        });
  }

  /** A route at every path a pattern matches whole, its handler given the match. */
  static Route matching(Method method, Pattern path, Handler handler) {
    return new Route(method, path, handler);
  }

  /** The match of a path, if this route answers it; null if it doesn't. */
  @Nullable Matcher match(String requested) {
    Matcher matcher = path.matcher(requested);
    if (!matcher.matches()) {
      return null;
    }
    return matcher;
  }

  /** Whether this route answers a request's method, as the request names it. */
  boolean answers(String requested) {
    return method.name().equals(requested);
  }
}
