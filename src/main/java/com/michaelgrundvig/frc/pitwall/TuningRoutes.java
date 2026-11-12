package com.michaelgrundvig.frc.pitwall;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Live tuning's routes: the session ({@code /api/tuning}), the configurations' documentation,
 * tunables and values, what differs from committed ({@code /api/changes}), and every other WPILib
 * tunable.
 */
final class TuningRoutes {
  private final Page state;
  private final Tuning tuning;
  private final TunableTee tunables;
  private final Requests requests;
  private final Responses responses;

  TuningRoutes(Page state, Requests requests, Responses responses) {
    this.state = state;
    tuning = state.tuning();
    tunables = state.tunables();
    this.requests = requests;
    this.responses = responses;
  }

  /** {@code GET /api/tuning}: the session. */
  void session(HttpExchange exchange) throws IOException {
    responses.json(exchange, 200, state.session());
  }

  /** {@code PATCH /api/tuning}: switches tuning on or off. */
  void patchSession(HttpExchange exchange) throws IOException {
    Map<String, @Nullable Object> patch = requests.object(exchange);
    for (String name : patch.keySet()) {
      if (!name.equals("enabled")) {
        throw new Problem(422, "Not changeable", "Only enabled can be changed, not " + name);
      }
    }
    if (!patch.containsKey("enabled")) {
      responses.json(exchange, 200, state.session()); // an empty patch changes nothing
      return;
    }
    if (!(patch.get("enabled") instanceof Boolean enabled)) {
      throw new Problem(400, "Not true or false", "enabled must be true or false");
    }
    responses.answer(exchange, tuning.requestEnabled(enabled), state::session);
  }

  /** {@code GET /api/configs}: each configuration's documentation. */
  void configs(HttpExchange exchange) throws IOException {
    responses.json(exchange, 200, Page.configs(tunables.configs()));
  }

  /** {@code GET /api/tunables}: the configurations' tunable values. */
  void tunables(HttpExchange exchange) throws IOException {
    responses.json(exchange, 200, Page.tunables(tuning.view()));
  }

  /** {@code GET /api/values}: the tunable values' current values. */
  void values(HttpExchange exchange) throws IOException {
    responses.json(exchange, 200, values());
  }

  private Map<String, Object> values() {
    return Page.values(tuning.view());
  }

  /**
   * {@code PATCH /api/values}: a JSON merge patch of several values at once, all or none; {@code
   * null} returns a value to its committed one.
   */
  void patchValues(HttpExchange exchange) throws IOException {
    Map<String, @Nullable Object> patch = requests.object(exchange);
    Map<String, Tuning.Value> known = new LinkedHashMap<>();
    for (Tuning.ConfigView config : tuning.view().configs()) {
      for (Tuning.Value value : config.values()) {
        known.put(value.path(), value);
      }
    }
    Map<String, Object> values = new LinkedHashMap<>();
    for (Map.Entry<String, @Nullable Object> field : patch.entrySet()) {
      Tuning.Value value = known.get(field.getKey());
      if (value == null) {
        throw new Problem(422, "Not tunable", "There is no tunable value at " + field.getKey());
      }
      values.put(value.path(), patched(value, field.getValue()));
    }
    // A configuration's values are WPILib tunables: the tee checks them whole, and applies them.
    responses.answer(exchange, tunables.requestChanges(values), this::values);
  }

  /** What a patch sets a value to: a finite number, or its committed one for null. */
  private static double patched(Tuning.Value value, @Nullable Object number) {
    if (number == null) {
      return value.committed();
    }
    if (number instanceof Number n && Double.isFinite(n.doubleValue())) {
      return n.doubleValue();
    }
    throw new Problem(
        400,
        "Not a number",
        value.path() + " must be a number, or null to return it to its committed value");
  }

  /** {@code GET /api/changes}: what differs from committed, as JSON or as the report's text. */
  void changes(HttpExchange exchange) throws IOException {
    String accept = exchange.getRequestHeaders().getFirst("Accept");
    if (accept != null && accept.contains("text/plain") && !accept.contains("json")) {
      List<String> report = tuning.report();
      responses.text(exchange, 200, "text/plain", String.join("\n", report) + "\n");
      return;
    }
    responses.json(exchange, 200, changeList());
  }

  /** {@code DELETE /api/changes}: returns every value to its committed one. */
  void resetChanges(HttpExchange exchange) throws IOException {
    responses.answer(exchange, tuning.requestReset(), this::changeList);
  }

  private List<Object> changeList() {
    List<Object> changes = new ArrayList<>();
    for (Tuning.ConfigView config : tuning.view().configs()) {
      for (LiveConfig.Change change : config.changes()) {
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("config", config.name());
        shown.put("field", change.path());
        shown.put("committed", change.committed());
        shown.put("tuned", change.tuned());
        shown.put("builderCall", change.builderCall());
        changes.add(shown);
      }
    }
    return changes;
  }

  /** {@code GET /api/wpilib-tunables}: every other WPILib tunable. */
  void wpilibTunables(HttpExchange exchange) throws IOException {
    responses.json(exchange, 200, wpilibTunables());
  }

  private List<Object> wpilibTunables() {
    List<Object> listed = new ArrayList<>();
    for (TunableTee.Listed tunable : tunables.view()) {
      Map<String, Object> shown = new LinkedHashMap<>();
      shown.put("path", tunable.path());
      shown.put("type", tunable.type());
      shown.put("value", tunable.value());
      shown.put("editable", tunable.editable());
      shown.put("limits", tunable.limits().equals(Limits.NONE) ? "" : tunable.limits().toString());
      Page.ends(shown, tunable.limits());
      listed.add(shown);
    }
    return listed;
  }

  /** {@code PATCH /api/wpilib-tunables}: several at once, all or none, each within its limits. */
  void patchWpilibTunables(HttpExchange exchange) throws IOException {
    Map<String, @Nullable Object> patch = requests.object(exchange);
    Map<String, Object> values = new LinkedHashMap<>();
    for (Map.Entry<String, @Nullable Object> field : patch.entrySet()) {
      if (!tunables.isEditable(field.getKey())) {
        throw new Problem(
            422, "Not changeable", "There is no tunable the page can change at " + field.getKey());
      }
      Object value = field.getValue();
      boolean number = value instanceof Number n && Double.isFinite(n.doubleValue());
      if (!(number || value instanceof Boolean)) {
        throw new Problem(400, "Not a value", field.getKey() + " takes a number or true/false");
      }
      values.put(field.getKey(), value);
    }
    responses.answer(exchange, tunables.requestChanges(values), this::wpilibTunables);
  }
}
