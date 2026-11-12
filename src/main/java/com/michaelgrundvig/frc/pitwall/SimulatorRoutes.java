package com.michaelgrundvig.frc.pitwall;

import com.michaelgrundvig.frc.pitwall.simulator.Components;
import com.michaelgrundvig.frc.pitwall.simulator.DriverStationState;
import com.michaelgrundvig.frc.pitwall.simulator.JoystickInput;
import com.michaelgrundvig.frc.pitwall.simulator.Setting;
import com.michaelgrundvig.frc.pitwall.simulator.Simulator;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.wpilib.hardware.hal.AllianceStationID;
import org.wpilib.hardware.hal.RobotMode;

/**
 * The simulator's routes ({@code /api/simulator...}): its Driver Station's state, a step of a
 * paused clock, stopping, the joysticks, and the faults. A robot has no simulator, and the server
 * answers 404 there before any of these runs.
 */
final class SimulatorRoutes {
  /** The modes the page may choose: a Driver Station's, not "no mode". */
  private static final Set<RobotMode> MODES =
      Set.of(RobotMode.AUTONOMOUS, RobotMode.TELEOPERATED, RobotMode.UTILITY);

  /**
   * The longest match time the page may set: ten minutes, room for any match period (a match is 2
   * min 30 s) and a long practice run.
   */
  private static final double MAX_MATCH_SECONDS = 600;

  private final Page state;
  private final Requests requests;
  private final Responses responses;
  private final Duration wait;

  SimulatorRoutes(Page state, Requests requests, Responses responses, Duration wait) {
    this.state = state;
    this.requests = requests;
    this.responses = responses;
    this.wait = wait;
  }

  /** {@code GET /api/simulator}: the simulator, its components and its scenarios. */
  void view(HttpExchange exchange) throws IOException {
    responses.json(exchange, 200, simulatorView());
  }

  /** {@code POST /api/simulator/step}: one loop of a paused simulator. */
  void step(HttpExchange exchange) throws IOException {
    responses.answer(exchange, simulator().step(), this::simulatorState);
  }

  /** {@code DELETE /api/simulator/faults}: undoes every lasting fault. */
  void undoAll(HttpExchange exchange) throws IOException {
    responses.answer(exchange, simulator().requestUndoAll(), this::componentsView);
  }

  /** The simulator's controls: there are none on a robot, and their routes are 404 there. */
  private Simulator simulator() {
    Simulator simulator = state.simulator();
    if (simulator == null) {
      throw new Problem(404, "Not found", "There is no simulator here");
    }
    return simulator;
  }

  /**
   * The simulator as the next loop shows it; while paused, when no loop comes, as the last loop
   * left it.
   */
  private Map<String, Map<String, Object>> simulatorView() {
    Simulator simulator = simulator();
    if (simulator.isPaused()) {
      return simulator.lastView(); // no loop is coming to read it
    }
    try {
      return simulator.requestView().get(wait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException e) {
      return simulator.lastView();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Problem(503, "Interrupted", "The server stopped waiting for the robot loop");
    }
  }

  private Object simulatorState() {
    return simulator().lastView().getOrDefault("simulator", Map.of());
  }

  private Object componentsView() {
    return simulator().lastView().getOrDefault("components", Map.of());
  }

  /**
   * {@code PATCH /api/simulator}: changes the Driver Station's state, all in one loop, in the order
   * given; resumes the clock at once, or pauses it once the rest is applied.
   */
  void patch(HttpExchange exchange) throws IOException {
    Simulator simulator = simulator();
    Map<String, @Nullable Object> patch = requests.object(exchange);
    UnaryOperator<DriverStationState> change = null;
    Boolean paused = null;
    for (Map.Entry<String, @Nullable Object> field : patch.entrySet()) {
      String name = field.getKey();
      Object value = field.getValue();
      if (name.equals("paused")) {
        paused = Requests.bool(name, value);
        continue;
      }
      UnaryOperator<DriverStationState> next = stationChange(name, value);
      UnaryOperator<DriverStationState> before = change;
      change = before == null ? next : station -> next.apply(before.apply(station));
    }
    List<CompletableFuture<RequestOutcome>> pending = new ArrayList<>();
    if (Boolean.FALSE.equals(paused)) {
      pending.add(simulator.resume()); // now: a paused clock runs no loop to do it
    }
    if (change != null) {
      pending.add(simulator.requestDriverStation(change));
    }
    if (Boolean.TRUE.equals(paused)) {
      pending.add(simulator.requestPause()); // once the rest is applied, in the same loop
    }
    if (pending.isEmpty()) {
      responses.json(exchange, 200, simulatorState()); // an empty patch changes nothing
      return;
    }
    responses.answer(exchange, all(pending), this::simulatorState);
  }

  /** What one of a patch's fields changes in the Driver Station's state. */
  private static UnaryOperator<DriverStationState> stationChange(
      String name, @Nullable Object value) {
    return switch (name) {
      case "enabled" -> {
        boolean enabled = Requests.bool(name, value);
        yield station -> station.withEnabled(enabled);
      }
      case "mode" -> {
        RobotMode mode = named(name, value, RobotMode.class, MODES);
        yield station -> station.withMode(mode);
      }
      case "allianceStation" -> {
        AllianceStationID at =
            named(name, value, AllianceStationID.class, Set.of(AllianceStationID.values()));
        yield station -> station.withAllianceStation(at);
      }
      case "fmsAttached" -> {
        boolean attached = Requests.bool(name, value);
        yield station -> station.withFmsAttached(attached);
      }
      case "dsAttached" -> {
        boolean attached = Requests.bool(name, value);
        yield station -> station.withDsAttached(attached);
      }
      case "matchTime" -> {
        double seconds = value == null ? -1 : matchTime(value);
        yield station -> station.withMatchTime(seconds);
      }
      default ->
          throw new Problem(
              422,
              "Not changeable",
              name
                  + " isn't the simulator's to change: enabled, mode, allianceStation,"
                  + " fmsAttached, dsAttached, matchTime, or paused");
    };
  }

  /** Done once every one is, with the first that wasn't applied, or applied. */
  private static CompletableFuture<RequestOutcome> all(
      List<CompletableFuture<RequestOutcome>> pending) {
    return CompletableFuture.allOf(pending.toArray(CompletableFuture<?>[]::new))
        .thenApply(
            done ->
                pending.stream()
                    .map(CompletableFuture::join)
                    .filter(outcome -> outcome.result() != RequestOutcome.Result.APPLIED)
                    .findFirst()
                    .orElse(RequestOutcome.applied()));
  }

  /**
   * {@code POST /api/simulator/stop}: says it's stopping, then has the loop stop the program: the
   * answer goes out first.
   */
  void stop(HttpExchange exchange) throws IOException {
    Simulator simulator = simulator();
    responses.json(exchange, 202, Map.of("detail", "Stopping the simulator"));
    simulator.stop();
  }

  /**
   * {@code PUT /api/simulator/joysticks}: hands each joystick's input to the robot loop, which
   * takes the latest.
   */
  void joysticks(HttpExchange exchange) throws IOException {
    Simulator simulator = simulator();
    Map<String, @Nullable Object> body = requests.object(exchange);
    Map<Integer, @Nullable JoystickInput> inputs = new LinkedHashMap<>();
    for (Map.Entry<String, @Nullable Object> joystick : body.entrySet()) {
      int port = JoystickRequests.port(joystick.getKey());
      Object value = joystick.getValue();
      if (value == null) {
        inputs.put(port, null);
      } else if (value instanceof Map<?, ?> input) {
        inputs.put(port, JoystickRequests.input(port, input));
      } else {
        throw new Problem(
            400, "Not a joystick's input", "Send {\"axes\", \"buttons\", \"povs\"}, or null");
      }
    }
    String stream = Requests.stream(exchange);
    if (stream == null) {
      throw new Problem(
          400,
          "No page to drive it",
          "Drive from Pitwall's page: a joystick's input names the page's event stream ("
              + PitwallServer.STREAM
              + "), and the joystick is let go when that page goes");
    }
    inputs.forEach((port, input) -> simulator.joystick(port, input, stream));
    responses.text(exchange, 204, "text/plain", "");
  }

  /** {@code POST /api/simulator/faults}: applies a fault, or undoes a lasting one. */
  void fault(HttpExchange exchange) throws IOException {
    Simulator simulator = simulator();
    Components components = simulator.components();
    Map<String, @Nullable Object> body = requests.object(exchange);
    if (!Set.of("component", "fault", "values", "undo").containsAll(body.keySet())
        || !(body.get("component") instanceof String component)
        || !(body.get("fault") instanceof String fault)) {
      throw new Problem(
          400,
          "A fault",
          "Send {\"component\", \"fault\", \"values\": {name: number}}, or {\"component\","
              + " \"fault\", \"undo\": true}");
    }
    if (Boolean.TRUE.equals(body.get("undo"))) {
      if (body.containsKey("values")) {
        throw new Problem(400, "Undo or apply", "Undoing a fault takes no values");
      }
      String why = components.whyNotUndo(component, fault);
      if (why != null) {
        throw new Problem(422, "Can't be undone", why);
      }
      responses.answer(exchange, simulator.requestUndo(component, fault), this::componentsView);
      return;
    }
    if (body.containsKey("undo") && !Boolean.FALSE.equals(body.get("undo"))) {
      throw new Problem(400, "Not true or false", "undo must be true or false");
    }
    Setting setting = Setting.of(component, fault, faultValues(body.get("values")));
    String why = components.whyNot(setting);
    if (why != null) {
      throw new Problem(422, "Can't be applied", why);
    }
    responses.answer(exchange, simulator.requestFault(setting), this::componentsView);
  }

  /** A fault's values, by name: none when it has none. */
  private static Map<String, Double> faultValues(@Nullable Object given) {
    Map<String, Double> values = new LinkedHashMap<>();
    if (given == null) {
      return values;
    }
    if (!(given instanceof Map<?, ?> map)) {
      throw new Problem(400, "Not values", "values is an object of numbers, by name");
    }
    for (Map.Entry<?, ?> value : map.entrySet()) {
      if (!(value.getValue() instanceof Number number)) {
        throw new Problem(400, "Not a number", value.getKey() + " must be a number");
      }
      values.put(String.valueOf(value.getKey()), number.doubleValue());
    }
    return values;
  }

  /** {@code POST /api/simulator/scenarios}: applies a scenario's faults. */
  void scenario(HttpExchange exchange) throws IOException {
    Simulator simulator = simulator();
    Map<String, @Nullable Object> body = requests.object(exchange);
    if (body.size() != 1 || !(body.get("apply") instanceof String name)) {
      throw new Problem(400, "Apply a scenario", "Send {\"apply\": name}");
    }
    if (!simulator.components().hasScenario(name)) {
      throw new Problem(422, "Not a scenario", "There is no scenario named " + name);
    }
    responses.answer(exchange, simulator.requestScenario(name), this::componentsView);
  }

  /** One of an enum's names, among those allowed: not text is 400, a name not allowed 422. */
  private static <E extends Enum<E>> E named(
      String name, @Nullable Object value, Class<E> type, Set<E> allowed) {
    if (!(value instanceof String given)) {
      throw new Problem(400, "Not a name", name + " is a name, such as " + type.getSimpleName());
    }
    for (E each : allowed) {
      if (each.name().equals(given)) {
        return each;
      }
    }
    throw new Problem(
        422,
        "Not a " + name,
        name
            + " is one of "
            + String.join(", ", allowed.stream().map(Enum::name).sorted().toList())
            + ", not "
            + value);
  }

  private static double matchTime(Object value) {
    if (!(value instanceof Number number)) {
      throw new Problem(400, "Not a number", "matchTime is a number of seconds, or null");
    }
    if (!(number.doubleValue() >= 0 && number.doubleValue() <= MAX_MATCH_SECONDS)) {
      throw new Problem(
          422,
          "Not a match time",
          "matchTime is seconds from 0 to " + MAX_MATCH_SECONDS + ", or null for none");
    }
    return number.doubleValue();
  }
}
