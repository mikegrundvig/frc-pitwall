package com.michaelgrundvig.frc.pitwall;

import com.michaelgrundvig.frc.pitwall.robot.RobotSession;
import com.michaelgrundvig.frc.pitwall.scene.Inspection;
import com.michaelgrundvig.frc.pitwall.simulator.Component;
import com.michaelgrundvig.frc.pitwall.simulator.Simulator;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scenes' routes: what a click on a shape says, and a scene's moving parts as a loop of the
 * robot's session had them.
 */
final class SceneRoutes {
  /** A scene's names, as {@link com.michaelgrundvig.frc.pitwall.scene.Scene} allows them. */
  static final String NAMES = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

  /** A click on a scene's shape: the scene's id, then the shape's. */
  static final Pattern INSPECT =
      Pattern.compile("/api/scenes/(" + NAMES + ")/inspect/(" + NAMES + ")");

  /** A scene as a loop of the robot's session had it: the scene's id. */
  static final Pattern STATE = Pattern.compile("/api/scenes/(" + NAMES + ")/state");

  private final Page state;
  private final ActionRoutes actions;
  private final Responses responses;

  SceneRoutes(Page state, ActionRoutes actions, Responses responses) {
    this.state = state;
    this.actions = actions;
    this.responses = responses;
  }

  /**
   * {@code GET /api/scenes/<id>/inspect/<shape>}: what a click on a scene's shape says, from what
   * the robot loop last published: {@code {"scene", "shape", "title", "details": [{"label",
   * "value"}], "actions": [action], "components": [name]}}, each action as {@code /api/actions}
   * gives it (one that isn't an action is left out), and each component one of the simulator's
   * parts the shape is, whose faults are in the page's state already (always none on a robot). 404
   * when there's nothing to say: no such scene, or a shape gone for more than two seconds.
   *
   * <p>With {@code ?index=} it says what the shape was in that loop of the robot's session, from
   * the session's memory: 404 when the scene has nothing to say of the shape in that loop.
   */
  void inspect(HttpExchange exchange, Matcher route) throws IOException {
    String scene = route.group(1);
    String shape = route.group(2);
    if (!state.scenes().has(scene)) {
      throw new Problem(404, "No such scene", "There is no scene " + scene);
    }
    Inspection inspection = inspection(exchange, scene, shape);
    List<Object> details = new ArrayList<>();
    for (Inspection.Detail detail : inspection.details()) {
      Map<String, Object> shown = new LinkedHashMap<>();
      shown.put("label", detail.label());
      shown.put("value", detail.value());
      details.add(shown);
    }
    Map<String, Object> shown = new LinkedHashMap<>();
    shown.put("scene", scene);
    shown.put("shape", shape);
    shown.put("title", inspection.title());
    shown.put("details", details);
    shown.put("actions", offeredActions(inspection));
    shown.put("components", partsThatCanFail(inspection));
    shown.put("offers", faultsOffered(inspection));
    responses.json(exchange, 200, shown);
  }

  /**
   * What the scene says of the shape, now or in the loop {@code ?index=} names; 404 for nothing.
   */
  private Inspection inspection(HttpExchange exchange, String scene, String shape) {
    Map<String, String> asked = Requests.query(exchange);
    boolean ofALoop = asked.containsKey("index");
    Optional<Inspection> said;
    try {
      if (ofALoop) {
        long loop = Requests.wholeNumber("index", asked.get("index"));
        said = state.scenes().inspectAt(scene, loop, shape);
      } else {
        said = state.scenes().inspect(scene, shape);
      }
    } catch (RuntimeException e) {
      throw new Problem(500, "Couldn't inspect", shape + ": " + e);
    }
    if (said.isEmpty() && ofALoop) {
      throw new Problem(
          404, "Nothing to say", "Nothing is known of " + shape + " in that loop of the session");
    }
    if (said.isEmpty()) {
      throw new Problem(
          404,
          "Nothing to say",
          "Nothing is known of " + shape + " now: what moves is kept for two seconds");
    }
    return said.get();
  }

  /** The actions a clicked shape offers, as {@code /api/actions} gives them, of those there are. */
  private List<Object> offeredActions(Inspection inspection) {
    List<Object> offered = new ArrayList<>();
    List<Object> all = actions.states();
    for (String name : inspection.actions()) {
      for (Object action : all) {
        if (action instanceof Map<?, ?> each && name.equals(each.get("name"))) {
          offered.add(action);
        }
      }
    }
    return offered;
  }

  /**
   * The simulator's parts a clicked shape is, of those it names: none on a robot, which has no
   * simulator, so its page is never offered a fault.
   */
  private List<String> partsThatCanFail(Inspection inspection) {
    Simulator simulator = state.simulator();
    if (simulator == null) {
      return List.of();
    }
    Set<String> known = declaredParts(simulator);
    // In the order the shape names them: the part itself first.
    List<String> parts = new ArrayList<>();
    for (String name : inspection.components()) {
      if (known.contains(name) && !parts.contains(name)) {
        parts.add(name);
      }
    }
    return parts;
  }

  /**
   * The faults a clicked shape offers to apply as they stand, of those whose part the simulator
   * has: none on a robot, so its page is never offered one.
   */
  private List<Object> faultsOffered(Inspection inspection) {
    Simulator simulator = state.simulator();
    List<Object> offered = new ArrayList<>();
    if (simulator == null) {
      return offered;
    }
    Set<String> known = declaredParts(simulator);
    for (Inspection.FaultOffer offer : inspection.offers()) {
      if (!known.contains(offer.component())) {
        continue;
      }
      Map<String, Object> shown = new LinkedHashMap<>();
      shown.put("label", offer.label());
      shown.put("component", offer.component());
      shown.put("fault", offer.fault());
      shown.put("values", offer.values());
      offered.add(shown);
    }
    return offered;
  }

  /** The names of the parts the simulation declared. */
  private static Set<String> declaredParts(Simulator simulator) {
    Set<String> known = new HashSet<>();
    for (Component component : simulator.components().components()) {
      known.add(component.name());
    }
    return known;
  }

  /**
   * {@code GET /api/scenes/<id>/state?index=}: a scene's moving parts as the loop {@code ?index=}
   * names had them, drawn from the robot's session: all of them, where the live state sends only
   * what changed. 404 for a scene that is not there, and for a loop the session does not hold.
   */
  void state(HttpExchange exchange, Matcher route) throws IOException {
    String scene = route.group(1);
    if (!state.scenes().has(scene)) {
      throw new Problem(404, "No such scene", "There is no scene " + scene);
    }
    Map<String, String> asked = Requests.query(exchange);
    if (!asked.containsKey("index")) {
      throw new Problem(
          400, "No loop asked for", "Name the loop: /api/scenes/" + scene + "/state?index=");
    }
    long loop = Requests.wholeNumber("index", asked.get("index"));
    Map<String, Object> said =
        state
            .scenes()
            .stateAt(scene, loop)
            .orElseThrow(
                () ->
                    new Problem(
                        404,
                        "No such loop",
                        "The session holds no loop " + loop + " for scene " + scene));
    Map<String, Object> shown = new LinkedHashMap<>();
    shown.put("index", loop);
    RobotSession session = state.robotSession();
    if (session != null) {
      session.timeOf(loop).ifPresent(nanos -> shown.put("timestampNanos", nanos));
    }
    shown.putAll(said);
    responses.json(exchange, 200, shown);
  }
}
