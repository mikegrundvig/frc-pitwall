package com.michaelgrundvig.frc.pitwall.robot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Robot view as the page is sent it: JSON objects, lists, texts and numbers.
 *
 * <p>The joints, sensors and gates are objects keyed by their names, so that a change to one value
 * of one joint is sent as that value alone (a JSON merge patch), with their order beside them. A
 * number is rounded to what the page can show, so a sensor's noise sends nothing, and one there is
 * none of (NaN) is left out, as JSON has no such number.
 *
 * <pre>{@code
 * {"robot", "index", "timestampNanos", "enabled", "mode",
 *  "order": [label, ...],
 *  "joints": {label: {"drawn", "carriedBy", "recorded",
 *      "scale": {"lower", "upper", "lowerShown", "upperShown", "softLower", "softUpper", "unit",
 *                "zeroAt", "turning"},
 *      "ticks": [{"name", "value", "shown", "asked"}],
 *      "bands": [{"lower", "upper", "shown", "emphasis", "reading", "rule", "dependsOn"}],
 *      "marks": [{"from", "to", "rule", "forJoint", "label", "sentence", "emphasis", "reading"}],
 *      "needles": {"now", "goal", "setpoint"},
 *      "facts": {"at", "told", "ownedBy", "lastMove", "state", "standing", "hold",
 *                "heldBy": [sentence]},
 *      "sensors": [{"label", "reading", "health"}]}},
 *  "sensorOrder": [label, ...],
 *  "sensors": {label: {"kind", "on", "health", "reading", "meters", "range",
 *      "marks": [{"meters", "forJoint", "label", "sentence", "emphasis", "reading"}]}},
 *  "gateOrder": [name, ...],
 *  "gates": {name: {"held", "blocking": [term], "why"}},
 *  "gamepads": {port: {"down": [button], "axes": [number], "pov"}},
 *  "fired": {"bindings": [text], "controls": [{"port", "control", "button"}], "timestampNanos"}}
 * }</pre>
 *
 * <p>Live, the page is sent that with {@code "session"} beside it ({@link #wire(RobotSession)}). A
 * loop asked for by its index has {@code "changed"} beside it instead ({@link #wire(Changed)}):
 * what changed in that loop, where the session holds the loop before it too.
 *
 * <p>{@code fired} is the latest firing of a binding within about a second before the moment, with
 * when it was: a firing lasts one loop, and the page keeps it in sight for a second. Beside each
 * binding is the control that fired it, so the page marks it on its gamepad: the gamepad's port,
 * the kind of control ({@code BUTTON}, {@code LEFT_TRIGGER}, {@code RIGHT_TRIGGER}, {@code
 * COMBINED}), and for a button its index, else -1.
 */
public final class RobotWire {
  private RobotWire() {}

  /**
   * A view, as the page is sent it.
   *
   * @param index the index of the view's loop in the session
   * @param lastFiring the latest firing of a binding shortly before the view's moment, if any
   */
  public static Map<String, Object> wire(RobotView view, long index, Optional<Moment> lastFiring) {
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("robot", view.robot());
    wire.put("index", index);
    wire.put("timestampNanos", view.timestampNanos());
    wire.put("enabled", view.enabled().name());
    wire.put("mode", view.mode().name());
    List<String> order = new ArrayList<>();
    Map<String, Object> joints = new LinkedHashMap<>();
    for (RobotView.JointCard joint : view.joints()) {
      order.add(joint.label());
      joints.put(joint.label(), wire(joint));
    }
    wire.put("order", order);
    wire.put("joints", joints);
    List<String> sensorOrder = new ArrayList<>();
    Map<String, Object> sensors = new LinkedHashMap<>();
    for (RobotView.SensorRow sensor : view.sensors()) {
      sensorOrder.add(sensor.label());
      sensors.put(sensor.label(), wire(sensor));
    }
    wire.put("sensorOrder", sensorOrder);
    wire.put("sensors", sensors);
    List<String> gateOrder = new ArrayList<>();
    Map<String, Object> gates = new LinkedHashMap<>();
    for (RobotView.GateRow gate : view.gates()) {
      gateOrder.add(gate.name());
      Map<String, Object> shown = new LinkedHashMap<>();
      shown.put("held", gate.held().name());
      shown.put("blocking", gate.blocking());
      shown.put("why", gate.why());
      gates.put(gate.name(), shown);
    }
    wire.put("gateOrder", gateOrder);
    wire.put("gates", gates);
    Map<String, Object> gamepads = new LinkedHashMap<>();
    for (RobotView.GamepadRow gamepad : view.gamepads()) {
      Map<String, Object> shown = new LinkedHashMap<>();
      shown.put("down", gamepad.down());
      shown.put("axes", gamepad.axes().stream().map(axis -> rounded(axis, 0.01)).toList());
      shown.put("pov", gamepad.pov());
      gamepads.put(String.valueOf(gamepad.port()), shown);
    }
    wire.put("gamepads", gamepads);
    List<Moment.Fired> firing = lastFiring.map(Moment::fired).orElse(List.of());
    List<Object> controls = new ArrayList<>();
    for (Moment.Fired each : firing) {
      Map<String, Object> control = new LinkedHashMap<>();
      control.put("port", each.port());
      control.put("control", each.control().name());
      control.put("button", each.button());
      controls.add(control);
    }
    Map<String, Object> fired = new LinkedHashMap<>();
    fired.put("bindings", firing.stream().map(Moment.Fired::binding).toList());
    fired.put("controls", controls);
    fired.put("timestampNanos", lastFiring.map(Moment::timestampNanos).orElse(0L));
    wire.put("fired", fired);
    return wire;
  }

  /**
   * What changed in a loop, compared with the loop before it, as the page is sent it with a loop it
   * asked for: {@code {"robot", "joints": {label: [row]}, "sensors": [label], "gates": [name]}},
   * each row one of {@link Changed.Row}.
   */
  public static Map<String, Object> wire(Changed changed) {
    Map<String, Object> joints = new LinkedHashMap<>();
    changed
        .joints()
        .forEach(
            (label, rows) -> joints.put(label, rows.stream().map(Enum::name).sorted().toList()));
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("robot", changed.robot());
    wire.put("joints", joints);
    wire.put("sensors", changed.sensors());
    wire.put("gates", changed.gates());
    return wire;
  }

  /**
   * How much of the session is remembered, as the page is sent it beside the newest loop: {@code
   * {"oldest", "oldestNanos", "startedNanos", "events"}}: the oldest loop the session holds and
   * when it was, when the session began, and how many events it has found. With the newest loop's
   * own index and time, it is the span a page's timeline covers.
   */
  public static Map<String, Object> wire(RobotSession session) {
    Map<String, Object> wire = new LinkedHashMap<>();
    long oldest = session.oldest();
    wire.put("oldest", oldest);
    session.timeOf(oldest).ifPresent(first -> wire.put("oldestNanos", first));
    session.startedNanos().ifPresent(started -> wire.put("startedNanos", started));
    wire.put("events", session.events().found());
    return wire;
  }

  /**
   * One event of the session, as the page is sent it: {@code {"number", "loop", "timestampNanos",
   * "kind", "subject", "about": [name], "severity", "sentence", "result"}}, the last for a move
   * that ended only.
   */
  public static Map<String, Object> wire(RobotEvent event) {
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("number", event.number());
    wire.put("loop", event.loop());
    wire.put("timestampNanos", event.timestampNanos());
    wire.put("kind", event.kind().name());
    wire.put("subject", event.subject().name());
    wire.put("about", event.about());
    wire.put("severity", event.severity().name());
    wire.put("sentence", event.sentence());
    event.moveResult().ifPresent(result -> wire.put("result", result.name()));
    return wire;
  }

  private static Map<String, Object> wire(RobotView.JointCard joint) {
    double step = stepOf(joint.scale().unit());
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("drawn", joint.drawn().name());
    wire.put("carriedBy", joint.carriedBy());
    wire.put("recorded", joint.recorded().name());
    wire.put("scale", wire(joint.scale(), step));
    List<Object> ticks = new ArrayList<>();
    for (RobotView.Tick tick : joint.ticks()) {
      Map<String, Object> shown = new LinkedHashMap<>();
      shown.put("name", tick.name());
      put(shown, "value", tick.value(), step);
      shown.put("shown", tick.shown());
      shown.put("asked", tick.asked().name());
      ticks.add(shown);
    }
    wire.put("ticks", ticks);
    List<Object> bands = new ArrayList<>();
    for (RobotView.Band band : joint.bands()) {
      Map<String, Object> shown = new LinkedHashMap<>();
      put(shown, "lower", band.lower(), step);
      put(shown, "upper", band.upper(), step);
      shown.put("shown", band.shown());
      shown.put("emphasis", band.emphasis().name());
      shown.put("reading", band.reading().name());
      shown.put("rule", band.rule());
      shown.put("dependsOn", band.dependsOn());
      bands.add(shown);
    }
    wire.put("bands", bands);
    List<Object> marks = new ArrayList<>();
    for (RobotView.Mark mark : joint.marks()) {
      Map<String, Object> shown = new LinkedHashMap<>();
      put(shown, "from", mark.from(), step);
      put(shown, "to", mark.to(), step);
      shown.put("rule", mark.rule().name());
      shown.put("forJoint", mark.forJoint());
      shown.put("label", mark.label());
      shown.put("sentence", mark.sentence());
      shown.put("emphasis", mark.emphasis().name());
      shown.put("reading", mark.reading().name());
      marks.add(shown);
    }
    wire.put("marks", marks);
    Map<String, Object> needles = new LinkedHashMap<>();
    put(needles, "now", joint.needles().now(), step);
    put(needles, "goal", joint.needles().goal(), step);
    put(needles, "setpoint", joint.needles().setpoint(), step);
    wire.put("needles", needles);
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("at", joint.facts().at());
    facts.put("told", joint.facts().told());
    facts.put("ownedBy", joint.facts().ownedBy());
    facts.put("lastMove", joint.facts().lastMove());
    facts.put("state", joint.facts().state());
    facts.put("standing", joint.facts().standing().name());
    facts.put("hold", joint.facts().hold().name());
    facts.put("heldBy", joint.facts().heldBy());
    wire.put("facts", facts);
    List<Object> sensors = new ArrayList<>();
    for (RobotView.OwnSensor sensor : joint.sensors()) {
      Map<String, Object> shown = new LinkedHashMap<>();
      shown.put("label", sensor.label());
      shown.put("reading", sensor.reading());
      shown.put("health", sensor.health().name());
      sensors.add(shown);
    }
    wire.put("sensors", sensors);
    return wire;
  }

  private static Map<String, Object> wire(RobotView.Scale scale, double step) {
    Map<String, Object> wire = new LinkedHashMap<>();
    put(wire, "lower", scale.lower(), step);
    put(wire, "upper", scale.upper(), step);
    wire.put("lowerShown", scale.lowerShown());
    wire.put("upperShown", scale.upperShown());
    put(wire, "softLower", scale.softLower(), step);
    put(wire, "softUpper", scale.softUpper(), step);
    wire.put("unit", scale.unit());
    put(wire, "zeroAt", scale.zeroAt(), 0.1);
    wire.put("turning", scale.turning().name());
    return wire;
  }

  private static Map<String, Object> wire(RobotView.SensorRow sensor) {
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("kind", sensor.kind().name());
    wire.put("on", sensor.on());
    wire.put("health", sensor.health().name());
    wire.put("reading", sensor.reading());
    put(wire, "meters", sensor.meters(), 0.001);
    put(wire, "range", sensor.range(), 0.001);
    List<Object> marks = new ArrayList<>();
    for (RobotView.SensorMark mark : sensor.marks()) {
      Map<String, Object> shown = new LinkedHashMap<>();
      put(shown, "meters", mark.meters(), 0.001);
      shown.put("forJoint", mark.forJoint());
      shown.put("label", mark.label());
      shown.put("sentence", mark.sentence());
      shown.put("emphasis", mark.emphasis().name());
      shown.put("reading", mark.reading().name());
      marks.add(shown);
    }
    wire.put("marks", marks);
    return wire;
  }

  /**
   * The finest step the page shows of a card's numbers: a tenth of a degree, a millimeter, a whole
   * rpm, a hundredth of a meter a second.
   */
  private static double stepOf(String unit) {
    return switch (unit) {
      case "°" -> 0.1;
      case "m" -> 0.001;
      case "rpm" -> 1;
      default -> 0.01;
    };
  }

  /** Puts a number, rounded to a step, unless there is none. */
  private static void put(Map<String, Object> into, String key, double value, double step) {
    if (Double.isFinite(value)) {
      into.put(key, rounded(value, step));
    }
  }

  /** A number rounded to a whole number of steps, never -0. */
  private static double rounded(double value, double step) {
    double steps = Math.rint(value / step);
    return steps == 0 ? 0 : Math.rint(steps * step * 1e6) / 1e6;
  }
}
