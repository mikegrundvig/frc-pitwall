package com.michaelgrundvig.frc.pitwall;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The page's own routes: {@code index.html} at {@code /}, its modules and styles in {@code /page/},
 * and the third-party files it loads from the robot.
 */
final class PageRoutes {
  /**
   * Third-party files the page loads from here, as the robot has no internet at an event: uPlot,
   * which draws the experiments' plots, and three.js, which draws the scenes (the World view loads
   * it only when it's first shown). Each is served if it's bundled; three.js is taken out of its
   * WebJar as Pitwall builds (pitwall/build.gradle).
   */
  private static final Map<String, String> BUNDLED =
      Map.of(
          "/uPlot.iife.min.js", "text/javascript",
          "/uPlot.min.css", "text/css",
          "/three/three.module.min.js", "text/javascript",
          "/three/three.core.min.js", "text/javascript",
          "/three/OrbitControls.js", "text/javascript");

  /** The bundled files' paths, each matched as it is. */
  static final Pattern BUNDLED_FILE =
      Pattern.compile(
          BUNDLED.keySet().stream().sorted().map(Pattern::quote).collect(Collectors.joining("|")));

  /**
   * The page's own files besides index.html: a script module for its core and one for each view,
   * and its styles, in {@code page/} beside it. A short plain name and nothing else, so no request
   * can reach any other resource.
   */
  static final Pattern PAGE_FILE = Pattern.compile("/page/[a-z][a-z0-9-]{0,31}\\.(js|css)");

  private final Responses responses;
  private final String index;
  private final Map<String, byte[]> bundled;

  /**
   * The page's files, each read once, as first asked for: they never change while it runs. Only
   * files found are kept, so a stranger asking for names that don't exist fills nothing.
   */
  private final Map<String, byte[]> pageFiles = new ConcurrentHashMap<>();

  /** Reads index.html and the bundled files: a Pitwall without its page doesn't start. */
  PageRoutes(Responses responses) {
    this.responses = responses;
    index = index();
    bundled = bundled();
  }

  /** {@code GET /}: the page. */
  void index(HttpExchange exchange) throws IOException {
    responses.text(exchange, 200, "text/html", index);
  }

  /**
   * A bundled third-party file, which the browser may keep: it's the same while the program runs.
   */
  void bundled(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    byte[] file = bundled.get(path);
    if (file == null) {
      throw new Problem(404, "Not bundled", path + " isn't bundled with Pitwall");
    }
    responses.headers(exchange, Objects.requireNonNull(BUNDLED.get(path)));
    exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
    exchange.sendResponseHeaders(200, file.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(file);
    }
  }

  /**
   * One of the page's files, from {@code page/} beside index.html. Not kept by the browser, so a
   * page reloaded after a redeploy runs the new program's page.
   */
  void pageFile(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String name = path.substring("/page/".length());
    byte[] file = pageFiles.get(name);
    if (file == null) {
      file =
          pageFile(name)
              .orElseThrow(() -> new Problem(404, "Not found", "There is nothing at " + path));
      pageFiles.putIfAbsent(name, file);
    }
    responses.headers(exchange, name.endsWith(".css") ? "text/css" : "text/javascript");
    exchange.sendResponseHeaders(200, file.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(file);
    }
  }

  /** The bundled files that are here, by path. */
  private static Map<String, byte[]> bundled() {
    Map<String, byte[]> found = new LinkedHashMap<>();
    for (String path : BUNDLED.keySet()) {
      try (InputStream in = PageRoutes.class.getResourceAsStream(path.substring(1))) {
        if (in != null) {
          found.put(path, in.readAllBytes());
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return found;
  }

  /** One of the page's files by name, such as {@code tune.js}; empty if there's none. */
  private static Optional<byte[]> pageFile(String name) {
    try (InputStream in = PageRoutes.class.getResourceAsStream("page/" + name)) {
      return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String index() {
    try (InputStream in = PageRoutes.class.getResourceAsStream("index.html")) {
      if (in == null) {
        throw new IllegalStateException("Pitwall's page (index.html) is missing");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
