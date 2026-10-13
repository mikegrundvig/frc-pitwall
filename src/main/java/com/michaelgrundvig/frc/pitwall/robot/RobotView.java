package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.record.ControlSentences;
import com.michaelgrundvig.frc.world.record.GateTerm;
import com.michaelgrundvig.frc.world.record.JointState;
import com.michaelgrundvig.frc.world.robot.ConditionReading;
import com.michaelgrundvig.frc.world.robot.RobotDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * The robot in one moment, as the page's Robot view draws it: a card for each joint, the robot's
 * other sensors, its gates and its gamepads. It is a function of the robot's definition and one
 * {@link Moment} and nothing else ({@link #of}), so the newest moment and any earlier one are drawn
 * by the same code.
 *
 * <p>Every number is in the units a person reads: degrees or meters for where a joint is, rpm or
 * meters a second for how fast, and beside each a text the model's own display functions made. A
 * number there is none of is NaN.
 *
 * @param robot the robot's name
 * @param timestampNanos when the moment was, on the robot's clock
 * @param enabled whether the robot was enabled
 * @param mode the mode the Driver Station chose
 * @param joints a card for each joint, in the order the model lists them
 * @param sensors the robot's switches and rangefinders, beyond each joint's own limit switches
 * @param gates each gate, with why it does not hold
 * @param gamepads the gamepads
 * @param fired the bindings a press or a release started in this loop, each with the control on the
 *     gamepad that fired it
 */
public record RobotView(
    String robot,
    long timestampNanos,
    Moment.Enabled enabled,
    Moment.Mode mode,
    List<JointCard> joints,
    List<SensorRow> sensors,
    List<GateRow> gates,
    List<GamepadRow> gamepads,
    List<Moment.Fired> fired) {
  public RobotView {
    joints = List.copyOf(joints);
    sensors = List.copyOf(sensors);
    gates = List.copyOf(gates);
    gamepads = List.copyOf(gamepads);
    fired = List.copyOf(fired);
  }

  /** The robot in one moment. */
  public static RobotView of(RobotDefinition definition, Moment moment) {
    return of(RobotPlan.of(definition), moment);
  }

  /** The robot in one moment, from what was already worked out of its definition. */
  static RobotView of(RobotPlan plan, Moment moment) {
    Causes causes = Causes.of(plan, moment);
    List<JointCard> joints = new ArrayList<>();
    for (RobotPlan.OfJoint joint : plan.joints()) {
      joints.add(JointCards.card(plan, joint, moment, causes));
    }
    List<GateRow> gates = new ArrayList<>();
    for (Moment.OfGate gate : moment.gates()) {
      gates.add(GateRow.of(plan.definition(), gate));
    }
    List<GamepadRow> gamepads = new ArrayList<>();
    for (Moment.OfGamepad gamepad : moment.gamepads()) {
      gamepads.add(GamepadRow.of(gamepad));
    }
    return new RobotView(
        plan.definition().name(),
        moment.timestampNanos(),
        moment.enabled(),
        moment.mode(),
        joints,
        SensorRows.rows(plan, moment, causes),
        gates,
        gamepads,
        moment.fired());
  }

  /** The card of the joint with a label. */
  public JointCard joint(String label) {
    for (JointCard joint : joints) {
      if (joint.label().equals(label)) {
        return joint;
      }
    }
    throw new IllegalArgumentException("the robot has no joint " + label);
  }

  /** The row of the sensor with a label. */
  public SensorRow sensor(String label) {
    for (SensorRow sensor : sensors) {
      if (sensor.label().equals(label)) {
        return sensor;
      }
    }
    throw new IllegalArgumentException("the view has no sensor " + label);
  }

  /** How a joint is drawn. */
  public enum Drawn {
    /** A joint that turns between stops and holds positions: its travel as an arc. */
    ARC,
    /** A joint that turns a whole turn or more and holds positions: a full circle. */
    CIRCLE,
    /** A joint run at speeds: a speedometer, from nothing to its top speed. */
    DIAL,
    /** A joint that slides: a straight bar. */
    BAR
  }

  /** Which way a joint that turns is drawn turning as its position grows. */
  public enum Turning {
    CLOCKWISE,
    COUNTERCLOCKWISE
  }

  /** Whether the record has a joint: the robot program drives it and logs it. */
  public enum Recorded {
    IN_THE_RECORD,
    NOT_IN_THE_RECORD
  }

  /** Whether a named place is the one a command asked for by name in this loop. */
  public enum Asked {
    ASKED_FOR,
    NOT_ASKED_FOR
  }

  /** What kind of interlock a mark stands for. */
  public enum Rule {
    /** The joint it restricts runs only while the condition holds. */
    RUNS_ONLY_WHEN,
    /** The joint it restricts is kept out of a region while the condition holds. */
    KEEPS_OUT
  }

  /** Whether a joint's interlocks leave it free to do what it is asked. */
  public enum Hold {
    /** No interlock restricts it. */
    FREE,
    /** Something holds it, and nothing asks anything of it: it is idle. */
    RESTRICTED,
    /** Something holds it short of what a command, or code outside any command, asked. */
    HELD
  }

  /** Whether something needs a person's attention. */
  public enum Health {
    FINE,
    NEEDS_ATTENTION
  }

  /**
   * What a joint's drawing spans.
   *
   * @param lower its low end: a hard stop, or the least speed drawn
   * @param upper its high end
   * @param lowerShown the low end as text
   * @param upperShown the high end as text
   * @param softLower the low soft limit, where the control has one inside the stops; else NaN
   * @param softUpper the high soft limit; else NaN
   * @param unit the unit of every number on the card: "°", "m", "rpm" or "m/s"
   * @param zeroAt for a joint that turns and holds positions, where its zero points on the card, in
   *     degrees counterclockwise from the right; for a joint that slides, the way it is drawn
   *     sliding as its position grows, the same way: 90 is up
   * @param turning for a joint that turns and holds positions, which way it is drawn turning as its
   *     position grows
   */
  public record Scale(
      double lower,
      double upper,
      String lowerShown,
      String upperShown,
      double softLower,
      double softUpper,
      String unit,
      double zeroAt,
      Turning turning) {}

  /**
   * A named place or a named output, marked on the drawing.
   *
   * @param name its name: "out", "intaking"
   * @param value where on the drawing
   * @param shown its value as text: "130°", "2000 rpm", "-4 V"
   * @param asked whether a command asked for it by name in this loop
   */
  public record Tick(String name, double value, String shown, Asked asked) {}

  /**
   * A keep-out's region on its own joint's card.
   *
   * @param lower the region's low edge
   * @param upper the region's high edge
   * @param shown the region as text: "-10° to 30°"
   * @param emphasis open, in force, or holding the joint
   * @param reading what its condition reads
   * @param rule the interlock in the model's words
   * @param dependsOn what its condition is about: a joint's label, or a sensor's
   */
  public record Band(
      double lower,
      double upper,
      String shown,
      Causes.Emphasis emphasis,
      ConditionReading reading,
      String rule,
      String dependsOn) {}

  /**
   * The condition of an interlock on another joint, marked on the card of the joint the condition
   * is about: the roller's "runs only when the pivot is at least 110°" is a mark at 110° on the
   * pivot's card.
   *
   * @param from the low end of where the condition holds, or NaN when it holds all the way down
   * @param to the high end of where the condition holds, or NaN when it holds all the way up
   * @param rule what kind of interlock it is
   * @param forJoint the joint the interlock restricts
   * @param label the mark in a few words: "roller runs only when at least 110°"
   * @param sentence the interlock in the model's words
   * @param emphasis quiet, in force, or what the other joint is waiting for
   * @param reading what the condition reads
   */
  public record Mark(
      double from,
      double to,
      Rule rule,
      String forJoint,
      String label,
      String sentence,
      Causes.Emphasis emphasis,
      ConditionReading reading) {}

  /**
   * Where a joint is, where it was told to go, and where its profile has it.
   *
   * @param now where it is, or how fast it runs: the solid needle
   * @param goal where it was told to go, or the speed asked: the dashed needle
   * @param setpoint where its profile has it: the lighter mark
   */
  public record Needles(double now, double goal, double setpoint) {}

  /**
   * A joint in words, the same facts the drawing shows.
   *
   * @param at where it is: "130°, at out", "1998 rpm, intaking", "unknown"; the place in the
   *     model's words
   * @param told where it was told to go: "out, 130°", or "nothing"
   * @param ownedBy who asked it, in the model's words, and for an idle joint what it does idle
   * @param lastMove how the last move on it ended, in the model's words, or "none yet"
   * @param state where it stands, in the model's words: "READY", "HOMING: toward its lower stop,
   *     for at most 9.5 s"
   * @param standing where it stands
   * @param hold whether its interlocks leave it free, restrict it while nothing asks, or hold it
   *     short of what a command asked
   * @param heldBy the things that restrict or hold it, each in the model's words
   */
  public record Facts(
      String at,
      String told,
      String ownedBy,
      String lastMove,
      String state,
      JointState.Standing standing,
      Hold hold,
      List<String> heldBy) {
    public Facts {
      heldBy = List.copyOf(heldBy);
    }
  }

  /**
   * One of a joint's own sensors: its motor controller's link, its absolute encoder, a limit
   * switch.
   *
   * @param label what it is: "motor controller", "absolute encoder", a switch's label
   * @param reading what it says: "answers", "magnet reads badly", "tripped"
   * @param health whether it needs attention
   */
  public record OwnSensor(String label, String reading, Health health) {}

  /**
   * One joint's card.
   *
   * @param label the joint's label
   * @param drawn how it is drawn
   * @param carriedBy the joint that carries it, or empty for one on the chassis
   * @param recorded whether the record has it
   * @param scale what its drawing spans
   * @param ticks its named places or outputs
   * @param bands the keep-outs on it
   * @param marks the conditions of interlocks on other joints that are about this one
   * @param needles where it is, where it was told to go, and where its profile has it
   * @param facts the same in words
   * @param sensors its own sensors
   */
  public record JointCard(
      String label,
      Drawn drawn,
      String carriedBy,
      Recorded recorded,
      Scale scale,
      List<Tick> ticks,
      List<Band> bands,
      List<Mark> marks,
      Needles needles,
      Facts facts,
      List<OwnSensor> sensors) {
    public JointCard {
      ticks = List.copyOf(ticks);
      bands = List.copyOf(bands);
      marks = List.copyOf(marks);
      sensors = List.copyOf(sensors);
    }

    /** The tick with a name. */
    public Tick tick(String name) {
      for (Tick tick : ticks) {
        if (tick.name().equals(name)) {
          return tick;
        }
      }
      throw new IllegalArgumentException(label + " has no tick " + name);
    }
  }

  /** What a sensor on the robot is. */
  public enum SensorKind {
    BEAM,
    CONTACT,
    LIMIT_SWITCH,
    RANGEFINDER
  }

  /**
   * A condition on a sensor, of an interlock on a joint.
   *
   * @param meters for a rangefinder, the distance the condition compares with; NaN for a switch
   * @param forJoint the joint the interlock restricts
   * @param label the mark in a few words: "arm runs only when tripped"
   * @param sentence the interlock in the model's words
   * @param emphasis quiet, in force, or what the joint is waiting for
   * @param reading what the condition reads
   */
  public record SensorMark(
      double meters,
      String forJoint,
      String label,
      String sentence,
      Causes.Emphasis emphasis,
      ConditionReading reading) {}

  /**
   * One switch or rangefinder.
   *
   * @param label the sensor's label
   * @param kind what it is
   * @param on what it is mounted on: a joint's label, or "chassis"
   * @param health whether it needs attention: it is lost, or the record does not have it
   * @param reading what it reads, in words: "tripped", "clear", "0.42 m", "lost"
   * @param meters a rangefinder's reading, or NaN
   * @param range the farthest a rangefinder reads, or NaN for a switch
   * @param marks the conditions of interlocks that read it
   */
  public record SensorRow(
      String label,
      SensorKind kind,
      String on,
      Health health,
      String reading,
      double meters,
      double range,
      List<SensorMark> marks) {
    public SensorRow {
      marks = List.copyOf(marks);
    }
  }

  /**
   * One gate.
   *
   * @param name the gate's name
   * @param held whether it holds
   * @param blocking the terms that keep it from holding, each in the model's words
   * @param why why it does not hold, in the model's words: "ready to feed is false: aimed at the
   *     Hub, shooter at goal"; empty while it holds
   */
  public record GateRow(String name, Moment.Held held, List<String> blocking, String why) {
    public GateRow {
      blocking = List.copyOf(blocking);
    }

    static GateRow of(RobotDefinition definition, Moment.OfGate gate) {
      List<String> blocking = new ArrayList<>();
      for (GateTerm term : gate.blocking()) {
        blocking.add(ControlSentences.gateTerm(definition, gate.name(), term, gate.blockingText()));
      }
      boolean holds = gate.held() == Moment.Held.HOLDS;
      String why = holds ? "" : ControlSentences.gateWhy(gate.name(), blocking);
      return new GateRow(gate.name(), gate.held(), blocking, why);
    }
  }

  /**
   * One gamepad.
   *
   * @param port its port on the Driver Station
   * @param down the buttons that are down, by number from 0
   * @param axes its axes, each from -1 to 1
   * @param pov its POV, as WPILib numbers its directions: 0 while it is centered; -1 when the
   *     record has none
   */
  public record GamepadRow(int port, List<Integer> down, List<Double> axes, int pov) {
    public GamepadRow {
      down = List.copyOf(down);
      axes = List.copyOf(axes);
    }

    static GamepadRow of(Moment.OfGamepad gamepad) {
      List<Integer> down = new ArrayList<>();
      for (int button = 0; button < Long.SIZE; button++) {
        if (gamepad.isDown(button)) {
          down.add(button);
        }
      }
      return new GamepadRow(gamepad.port(), down, gamepad.axes(), gamepad.pov());
    }
  }
}
