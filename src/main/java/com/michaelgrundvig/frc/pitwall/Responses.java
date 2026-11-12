package com.michaelgrundvig.frc.pitwall;

import com.sun.net.httpserver.HttpExchange;
import io.avaje.jsonb.JsonType;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * How the server answers: JSON, text, a file to save, a problem (RFC 9457), and a change's outcome
 * once the robot loop has applied it. Every answer carries the same headers ({@link #headers}).
 */
final class Responses {
  private final JsonType<Object> json;
  private final Duration wait;
  private final Devices devices;

  /**
   * @param json writes the answers' JSON
   * @param wait how long a change waits for the robot loop before answering 202
   * @param devices the devices, whose cards' pictures the page may load from where they declared
   */
  Responses(JsonType<Object> json, Duration wait, Devices devices) {
    this.json = json;
    this.wait = wait;
    this.devices = devices;
  }

  /** An answer of JSON. */
  void json(HttpExchange exchange, int status, Object body) throws IOException {
    text(exchange, status, "application/json", json.toJson(body));
  }

  /** An answer of text of a type; an empty one has no body. */
  void text(HttpExchange exchange, int status, String type, String body) throws IOException {
    bytes(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
  }

  /** An answer of bytes of a type, to show; an empty one has no body. */
  void bytes(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
    headers(exchange, type);
    exchange.sendResponseHeaders(status, body.length > 0 ? body.length : -1);
    if (body.length == 0) {
      return;
    }
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  /** A file, to save rather than show: never shown in the page, whatever it holds. */
  void file(HttpExchange exchange, String name, String type, byte[] bytes) throws IOException {
    attachment(exchange, name, type);
    exchange.sendResponseHeaders(200, bytes.length > 0 ? bytes.length : -1);
    if (bytes.length == 0) {
      return;
    }
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** The headers of a file to save rather than show. */
  void attachment(HttpExchange exchange, String name, String type) {
    headers(exchange, type);
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange
        .getResponseHeaders()
        .set("Content-Disposition", "attachment; filename=\"" + name + "\"");
  }

  /** Waits for the robot loop, then answers with what became of the request. */
  void answer(
      HttpExchange exchange, CompletableFuture<RequestOutcome> pending, Supplier<Object> state)
      throws IOException {
    RequestOutcome outcome;
    try {
      outcome = pending.get(wait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      json(
          exchange,
          202,
          Map.of("detail", "Queued for the robot loop; the next GET shows the result"));
      return;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Problem(503, "Interrupted", "The server stopped waiting for the robot loop");
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
    switch (outcome.result()) {
      case APPLIED -> json(exchange, 200, state.get());
      case REFUSED -> throw new Problem(422, "Refused", outcome.detail(), outcome.reasons());
      case LOCKED -> throw new Problem(409, "Locked", outcome.detail());
    }
  }

  /** A problem's answer: its status, and its details as JSON. */
  void problem(HttpExchange exchange, Problem problem) throws IOException {
    if (problem.retryAfterSeconds() > 0) {
      exchange
          .getResponseHeaders()
          .set("Retry-After", Integer.toString(problem.retryAfterSeconds()));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", problem.title());
    body.put("status", problem.status());
    body.put("detail", String.valueOf(problem.getMessage()));
    if (!problem.reasons().isEmpty()) {
      // An extension member (RFC 9457 allows them): each reason, tied to its values.
      body.put("errors", problem.reasons().stream().map(Responses::error).toList());
    }
    text(exchange, problem.status(), "application/problem+json", json.toJson(body));
  }

  /**
   * A reason a change was refused, as the page reads it: which check ({@code layer}: value, limit,
   * record, or tunable), who refused it ({@code by}: a record), the tunables it concerns ({@code
   * paths}, and {@code path} when it's one), its configuration and, for a record's check that names
   * no value, where the record is ({@code group}), why ({@code text}), and its line of {@code
   * detail} ({@code line}).
   */
  private static Map<String, Object> error(RequestOutcome.Reason reason) {
    Map<String, Object> shown = new LinkedHashMap<>();
    shown.put("layer", reason.layer().name().toLowerCase(Locale.ROOT));
    shown.put("by", reason.by());
    if (reason.paths().size() == 1) {
      shown.put("path", reason.paths().getFirst());
    }
    shown.put("paths", reason.paths());
    shown.put("config", reason.config());
    shown.put("group", reason.group());
    shown.put("text", reason.text());
    shown.put("line", reason.line());
    return shown;
  }

  /**
   * The headers every answer carries. The page may not be framed by another site's page (to trick a
   * click on its buttons), and loads nothing from anywhere else but the cards' pictures, which the
   * browser loads straight from their devices (Devices.Picture), such as a coprocessor's camera
   * stream: only from the places the robot program declared ({@link Devices#picturesFrom}), never
   * from any other host. It runs no inline script or style: what a robot's text could slip into the
   * page (it's always set as text) couldn't run.
   */
  void headers(HttpExchange exchange, String type) {
    exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
    exchange.getResponseHeaders().set("Content-Security-Policy", policy());
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
  }

  /** The page's Content-Security-Policy: everything from the robot, pictures from their devices. */
  private String policy() {
    StringBuilder images = new StringBuilder("img-src 'self'");
    for (String origin : devices.pictureOrigins()) {
      images.append(' ').append(origin);
    }
    return "default-src 'self'; " + images + "; frame-ancestors 'none'";
  }
}
