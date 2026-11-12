package com.michaelgrundvig.frc.pitwall;

import com.michaelgrundvig.frc.pitwall.experiment.Experiments;
import com.michaelgrundvig.frc.pitwall.experiment.Run;
import com.michaelgrundvig.frc.pitwall.push.Broadcaster;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The experiments' routes: the rigs and their experiments, starting and canceling one, the run, and
 * the heartbeat that keeps a run going.
 */
final class ExperimentRoutes {
  /** The longest reason a cancel may give. */
  private static final int MAX_REASON = 200;

  private final Page state;
  private final Experiments experiments;
  private final Requests requests;
  private final Responses responses;

  ExperimentRoutes(Page state, Requests requests, Responses responses) {
    this.state = state;
    experiments = state.experiments();
    this.requests = requests;
    this.responses = responses;
  }

  /** {@code GET /api/experiments}: the rigs and the experiments that run on them. */
  void list(HttpExchange exchange) throws IOException {
    responses.json(exchange, 200, state.experimentsView());
  }

  /** {@code GET /api/run}: the run in progress, or the latest to end, with its trace. */
  void run(HttpExchange exchange) throws IOException {
    responses.json(exchange, 200, run(true));
  }

  /**
   * {@code POST /api/heartbeat}: nothing, but it keeps a run going, as every request from the page
   * that started it does.
   */
  void heartbeat(HttpExchange exchange) throws IOException {
    responses.text(exchange, 204, "text/plain", "");
  }

  /**
   * {@code POST /api/experiments}: starts or cancels an experiment. A start names the page's
   * stream, which the deadman watches: only a page following the robot can keep a run going. A
   * cancel names the run, so one meant for a run that has just ended never stops the next, and may
   * say why.
   */
  void startOrCancel(HttpExchange exchange) throws IOException {
    Map<String, @Nullable Object> body = requests.object(exchange);
    Object start = body.get("start");
    Object cancel = body.get("cancel");
    Object name = start != null ? start : cancel;
    Set<String> allowed = start != null ? Set.of("start") : Set.of("cancel", "run", "reason");
    if (name == null || (start != null && cancel != null) || !allowed.containsAll(body.keySet())) {
      throw new Problem(
          400,
          "Start or cancel",
          "Send {\"start\": name}, or {\"cancel\": name, \"run\": id, \"reason\": why}");
    }
    if (!(name instanceof String experiment)) {
      throw new Problem(400, "Not a name", "An experiment's name is a string");
    }
    if (!experiments.has(experiment)) {
      throw new Problem(422, "Not an experiment", "There is no experiment named " + experiment);
    }
    if (start != null) {
      start(exchange, experiment);
      return;
    }
    cancel(exchange, experiment, body);
  }

  private void start(HttpExchange exchange, String experiment) throws IOException {
    String stream = Requests.stream(exchange);
    if (stream == null) {
      throw new Problem(
          400,
          "No page to keep it going",
          "Start experiments from Pitwall's page: a start names the page's event stream ("
              + PitwallServer.STREAM
              + "), and the run goes on only while that page keeps in touch");
    }
    responses.answer(exchange, experiments.requestStart(experiment, stream), () -> run(false));
  }

  private void cancel(HttpExchange exchange, String experiment, Map<String, @Nullable Object> body)
      throws IOException {
    long runId = 0;
    if (body.get("run") instanceof Number number) {
      runId = number.longValue();
    } else if (body.containsKey("run")) {
      throw new Problem(400, "Not a run", "A run is named by its number");
    }
    Object reason = body.getOrDefault("reason", "");
    if (!(reason instanceof String why) || why.length() > MAX_REASON) {
      throw new Problem(400, "Not a reason", "A reason is a sentence of text");
    }
    if (!isRunning(experiment, runId)) {
      throw new Problem(409, "Not running", experiment + " isn't running");
    }
    responses.answer(exchange, experiments.requestCancel(experiment, runId, why), () -> run(false));
  }

  /** Whether an experiment is running now, as the run it names if it names one (not 0). */
  private boolean isRunning(String experiment, long runId) {
    Run running = experiments.run();
    if (running == null) {
      return false;
    }
    if (running.state().phase() != Run.Phase.RUNNING) {
      return false;
    }
    if (!running.experiment().name().equals(experiment)) {
      return false;
    }
    return runId == 0 || running.id() == runId;
  }

  /** The run in progress, or the latest to end; with its trace so far, if asked. */
  private Map<String, Object> run(boolean withTrace) {
    Run run = experiments.run();
    if (run == null) {
      throw new Problem(404, "No run", "No experiment has run yet");
    }
    Map<String, Object> shown = new LinkedHashMap<>(state.run(run));
    if (withTrace) {
      shown.put("trace", Broadcaster.trace(run));
    }
    return shown;
  }
}
