package com.michaelgrundvig.frc.pitwall;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/** The status items' route, and the actions': where each stands, and starting or canceling one. */
final class ActionRoutes {
  private final Status status;
  private final Actions actions;
  private final Requests requests;
  private final Responses responses;
  private final Duration wait;

  ActionRoutes(Page state, Requests requests, Responses responses, Duration wait) {
    status = state.status();
    actions = state.actions();
    this.requests = requests;
    this.responses = responses;
    this.wait = wait;
  }

  /** {@code GET /api/status}: the status items, read by the next loop. */
  void status(HttpExchange exchange) throws IOException {
    List<Object> items = new ArrayList<>();
    for (Status.Item item : readStatus()) {
      Map<String, Object> shown = new LinkedHashMap<>();
      shown.put("name", item.name());
      shown.put("value", item.value());
      items.add(shown);
    }
    responses.json(exchange, 200, items);
  }

  /**
   * The status items, read by the next loop. A loop that doesn't get to it in time (stopped, or
   * paused in a debugger) is 503: an old reading passed off as now would mislead.
   */
  private List<Status.Item> readStatus() {
    try {
      return status.requestRead().get(wait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException e) {
      throw new Problem(
          503,
          "No reading",
          "The robot loop didn't read the status within "
              + wait.toMillis()
              + " ms: it may be stopped or paused");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Problem(503, "Interrupted", "The server stopped waiting for the robot loop");
    }
  }

  /** {@code GET /api/actions}: every action and where it stands. */
  void list(HttpExchange exchange) throws IOException {
    responses.json(exchange, 200, states());
  }

  /** {@code POST /api/actions}: {@code {"start": name}} or {@code {"cancel": name}}. */
  void startOrCancel(HttpExchange exchange) throws IOException {
    Map<String, @Nullable Object> body = requests.object(exchange);
    Object start = body.get("start");
    Object cancel = body.get("cancel");
    Object name = start != null ? start : cancel;
    if (body.size() != 1 || name == null) {
      throw new Problem(400, "Start or cancel", "Send {\"start\": name} or {\"cancel\": name}");
    }
    if (!(name instanceof String action)) {
      throw new Problem(400, "Not a name", "An action's name is a string");
    }
    if (!actions.has(action)) {
      throw new Problem(422, "Not an action", "There is no action named " + action);
    }
    if (start != null) {
      responses.answer(exchange, actions.requestStart(action), this::states);
      return;
    }
    responses.answer(exchange, actions.requestCancel(action), this::states);
  }

  /** Every action and where it stands, as {@code GET /api/actions} gives them. */
  List<Object> states() {
    List<Object> states = new ArrayList<>();
    for (Actions.State state : actions.view()) {
      Map<String, Object> shown = new LinkedHashMap<>();
      shown.put("name", state.name());
      shown.put("phase", state.phase().name());
      shown.put("detail", state.detail());
      shown.put("locked", state.locked());
      shown.put("when", state.when().name());
      states.add(shown);
    }
    return states;
  }
}
