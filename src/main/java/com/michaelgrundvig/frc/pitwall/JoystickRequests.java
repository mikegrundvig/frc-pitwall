package com.michaelgrundvig.frc.pitwall;

import com.michaelgrundvig.frc.pitwall.simulator.JoystickInput;
import com.michaelgrundvig.frc.pitwall.simulator.Simulator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A joystick's input as the page sends it to {@code PUT /api/simulator/joysticks}: its port, and
 * its axes, buttons and POVs, each checked, or refused as a {@link Problem}.
 */
final class JoystickRequests {
  private JoystickRequests() {}

  /** A joystick's port, by its name in the body: "0" to one less than the simulator has. */
  static int port(String name) {
    for (int port = 0; port < Simulator.JOYSTICKS; port++) {
      if (name.equals(Integer.toString(port))) {
        return port;
      }
    }
    throw new Problem(
        422,
        "Not a joystick",
        "The page drives joysticks 0 to " + (Simulator.JOYSTICKS - 1) + ", not " + name);
  }

  /** A joystick's whole input: {@code {"axes", "buttons", "povs"}}. */
  static JoystickInput input(int port, Map<?, ?> input) {
    if (!Set.of("axes", "buttons", "povs").containsAll(input.keySet())) {
      throw new Problem(
          400, "Not a joystick's input", "A joystick's input is its axes, buttons, and povs");
    }
    List<Double> axes = axes(input.get("axes"));
    List<Boolean> buttons = buttons(input.get("buttons"));
    List<Integer> povs = povs(port, input.get("povs"));
    if (axes.size() > JoystickInput.MAX_AXES
        || buttons.size() > JoystickInput.MAX_BUTTONS
        || povs.size() > JoystickInput.MAX_POVS) {
      throw new Problem(
          400,
          "Not a joystick's input",
          "A joystick has at most "
              + JoystickInput.MAX_AXES
              + " axes, "
              + JoystickInput.MAX_BUTTONS
              + " buttons, and "
              + JoystickInput.MAX_POVS
              + " POVs");
    }
    String why = JoystickInput.whyNot(axes, buttons, povs);
    if (why != null) {
      throw new Problem(422, "Out of range", "Joystick " + port + ": " + why);
    }
    return new JoystickInput(axes, buttons, povs);
  }

  private static List<Double> axes(@Nullable Object given) {
    List<Double> axes = new ArrayList<>();
    for (Object axis : list(given)) {
      if (!(axis instanceof Number number)) {
        throw new Problem(400, "Not a joystick's input", "Each axis is a number");
      }
      axes.add(number.doubleValue());
    }
    return axes;
  }

  private static List<Boolean> buttons(@Nullable Object given) {
    List<Boolean> buttons = new ArrayList<>();
    for (Object button : list(given)) {
      if (!(button instanceof Boolean pressed)) {
        throw new Problem(400, "Not a joystick's input", "Each button is true or false");
      }
      buttons.add(pressed);
    }
    return buttons;
  }

  private static List<Integer> povs(int port, @Nullable Object given) {
    List<Integer> povs = new ArrayList<>();
    for (Object pov : list(given)) {
      if (!(pov instanceof Number number)) {
        throw new Problem(400, "Not a joystick's input", "Each POV is a number of degrees");
      }
      if (number.doubleValue() != number.intValue()) {
        throw new Problem(
            422, "Not a POV", "Joystick " + port + ": a POV is a whole number of degrees");
      }
      povs.add(number.intValue());
    }
    return povs;
  }

  private static List<?> list(@Nullable Object value) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new Problem(400, "Not a joystick's input", "axes, buttons, and povs are lists");
    }
    return list;
  }
}
