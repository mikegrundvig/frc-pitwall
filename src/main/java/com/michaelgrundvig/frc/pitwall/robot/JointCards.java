package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.pitwall.robot.RobotView.Asked;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Band;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Drawn;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Facts;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Health;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Hold;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.JointCard;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Mark;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Needles;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.OwnSensor;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Recorded;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Rule;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Scale;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Tick;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Turning;
import com.michaelgrundvig.frc.world.config.Rules;
import com.michaelgrundvig.frc.world.physical.Shape;
import com.michaelgrundvig.frc.world.physical.Vector3;
import com.michaelgrundvig.frc.world.record.Holder;
import com.michaelgrundvig.frc.world.record.JointOwner;
import com.michaelgrundvig.frc.world.record.JointPlace;
import com.michaelgrundvig.frc.world.record.JointSentences;
import com.michaelgrundvig.frc.world.record.JointState;
import com.michaelgrundvig.frc.world.record.MoveSentences;
import com.michaelgrundvig.frc.world.robot.Condition;
import com.michaelgrundvig.frc.world.robot.Interlock;
import com.michaelgrundvig.frc.world.robot.Joint;
import com.michaelgrundvig.frc.world.robot.JointAsk;
import com.michaelgrundvig.frc.world.robot.MechanismControl;
import com.michaelgrundvig.frc.world.robot.Output;
import com.michaelgrundvig.frc.world.robot.Position;
import com.michaelgrundvig.frc.world.robot.Range;
import com.michaelgrundvig.frc.world.robot.RobotDefinition;
import com.michaelgrundvig.frc.world.robot.Sensor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Makes one joint's card for one moment. */
final class JointCards {
  private JointCards() {}

  /** A whole turn, in radians. */
  private static final double TURN = 2 * Math.PI;

  /** How far from its axle a link's shape must be centered to say which way the link points. */
  private static final double LINK_POINTS_FROM = 0.01;

  static JointCard card(RobotPlan plan, RobotPlan.OfJoint planned, Moment moment, Causes causes) {
    Joint joint = planned.joint();
    Drawn drawn = drawn(joint);
    Display display = Display.of(joint, drawn);
    Moment.OfJoint now = moment.joint(joint.label());
    String goalName = now == null ? "" : now.goalName();
    return new JointCard(
        joint.label(),
        drawn,
        joint.parent() instanceof Joint carrier ? carrier.label() : "",
        now == null ? Recorded.NOT_IN_THE_RECORD : Recorded.IN_THE_RECORD,
        scale(planned, drawn, display),
        ticks(planned, drawn, display, goalName),
        bands(planned, display, causes),
        marks(planned, drawn, display, causes),
        now == null ? NO_NEEDLES : needles(planned, drawn, display, now),
        now == null ? NOT_RECORDED : facts(plan, planned, drawn, moment, now),
        now == null ? List.of() : ownSensors(plan, joint, moment, now));
  }

  private static final Needles NO_NEEDLES = new Needles(Double.NaN, Double.NaN, Double.NaN);

  private static final Facts NOT_RECORDED =
      new Facts(
          "not in the record", "", "", "", "", JointState.Standing.UNHOMED, Hold.FREE, List.of());

  /** How a joint is drawn, by its kind. */
  static Drawn drawn(Joint joint) {
    if (!joint.holdsPositions()) {
      return Drawn.DIAL;
    }
    if (joint instanceof Joint.Prismatic) {
      return Drawn.BAR;
    }
    boolean turnsRightRound = joint.range().isEmpty() || joint.range().get().width() >= TURN - 1e-9;
    return turnsRightRound ? Drawn.CIRCLE : Drawn.ARC;
  }

  /**
   * How a card's numbers are shown: a joint that holds positions in degrees or meters, and one run
   * at speeds in rpm or meters a second. The texts are the model's own.
   */
  private record Display(Joint joint, boolean speeds, boolean turns) {
    static Display of(Joint joint, Drawn drawn) {
      return new Display(joint, drawn == Drawn.DIAL, joint instanceof Joint.Revolute);
    }

    /** A value of the joint's, in its units, as a number on its card. */
    double number(double value) {
      if (!turns) {
        return value;
      }
      return speeds ? value * 60 / TURN : Math.toDegrees(value);
    }

    /** The same value as text. */
    String text(double value) {
      return speeds ? speedText(joint, value) : joint.shown(value);
    }

    String unit() {
      if (speeds) {
        return turns ? "rpm" : "m/s";
      }
      return turns ? "°" : "m";
    }
  }

  /**
   * A speed as text, to the whole rpm or the hundredth of a meter a second: a speed measured on the
   * robot then reads as how fast the joint runs and not as its sensor's noise.
   */
  private static String speedText(Joint joint, double speed) {
    if (joint instanceof Joint.Revolute) {
      double wholeRpm = Math.rint(speed * 60 / TURN);
      return joint.shownSpeed(wholeRpm * TURN / 60);
    }
    return joint.shownSpeed(Math.rint(speed * 100) / 100);
  }

  // ---- What the drawing spans ----

  private static Scale scale(RobotPlan.OfJoint planned, Drawn drawn, Display display) {
    Joint joint = planned.joint();
    double lower;
    double upper;
    switch (drawn) {
      case DIAL -> {
        upper = topSpeed(planned);
        lower = runsBothWays(joint) ? -upper : 0;
      }
      case CIRCLE -> {
        lower = joint.range().map(Range::lower).orElse(-Math.PI);
        upper = joint.range().map(Range::upper).orElse(Math.PI);
      }
      default -> {
        lower = joint.range().map(Range::lower).orElse(leastPlace(joint));
        upper = joint.range().map(Range::upper).orElse(greatestPlace(joint));
      }
    }
    Optional<MechanismControl.SoftLimits> soft =
        joint.driven().control().flatMap(MechanismControl::softLimits);
    boolean softInside = soft.isPresent() && drawn != Drawn.DIAL;
    double softLower = softInside && soft.get().lower() > lower ? soft.get().lower() : Double.NaN;
    double softUpper = softInside && soft.get().upper() < upper ? soft.get().upper() : Double.NaN;
    return new Scale(
        display.number(lower),
        display.number(upper),
        display.text(lower),
        display.text(upper),
        display.number(softLower),
        display.number(softUpper),
        display.unit(),
        zeroAt(joint),
        turning(joint));
  }

  /**
   * The top of a speedometer: the joint's free speed, its motors' through its gearing. A joint no
   * motors of its own drive has none, and is drawn to the fastest of its named speeds.
   */
  private static double topSpeed(RobotPlan.OfJoint planned) {
    if (Double.isFinite(planned.freeSpeed())) {
      return planned.freeSpeed();
    }
    double fastest = 1;
    for (Output output : planned.joint().outputs()) {
      if (output.kind() == Output.Kind.VELOCITY) {
        fastest = Math.max(fastest, Math.abs(output.value()));
      }
    }
    return fastest;
  }

  /** Whether a joint run at speeds has a named output that runs it backward. */
  private static boolean runsBothWays(Joint joint) {
    return joint.outputs().stream().anyMatch(output -> output.value() < 0);
  }

  private static double leastPlace(Joint joint) {
    return joint.positions().stream().mapToDouble(Position::value).min().orElse(0);
  }

  private static double greatestPlace(Joint joint) {
    double greatest = joint.positions().stream().mapToDouble(Position::value).max().orElse(1);
    return greatest > leastPlace(joint) ? greatest : leastPlace(joint) + 1;
  }

  /**
   * Where a joint's zero points on its card, in degrees counterclockwise from the right.
   *
   * <p>A joint that pitches is drawn from the robot's side, its front to the right. Its link then
   * points, at zero, wherever its shape is centered from its axle: up for an intake that stands
   * upright when it is in. A link with no shape, or one centered on its axle, points forward, as
   * the model's pitch axes say. Any other joint that turns has its zero drawn pointing up. For a
   * joint that slides, this is the way it slides ({@link #slidesToward}).
   */
  private static double zeroAt(Joint joint) {
    if (joint instanceof Joint.Prismatic) {
      return slidesToward(joint.axis());
    }
    if (!pitches(joint)) {
      return 90;
    }
    for (Shape shape : joint.link().shapes()) {
      double forward = shape.pose().getX();
      double up = shape.pose().getZ();
      if (Math.hypot(forward, up) > LINK_POINTS_FROM) {
        return Math.toDegrees(Math.atan2(up, forward));
      }
    }
    return 0;
  }

  /**
   * The way a joint that slides is drawn sliding as its position grows, in degrees counterclockwise
   * from the right, seen from the robot's side with its front to the right: up for an elevator, and
   * to the right for a slide that reaches forward. One that slides across the robot is drawn
   * sliding to the right.
   */
  private static double slidesToward(Vector3 axis) {
    boolean mostlyUpOrDown = Math.abs(axis.z()) >= Math.max(Math.abs(axis.x()), Math.abs(axis.y()));
    if (mostlyUpOrDown) {
      return axis.z() >= 0 ? 90 : 270;
    }
    boolean mostlyBackward = Math.abs(axis.x()) >= Math.abs(axis.y()) && axis.x() < 0;
    return mostlyBackward ? 180 : 0;
  }

  /**
   * Which way a joint is drawn turning as its position grows. Seen from the robot's side with its
   * front to the right, a joint that pitches up turns counterclockwise and one that pitches down
   * clockwise. Any other joint is drawn turning counterclockwise, as angles are in mathematics.
   */
  private static Turning turning(Joint joint) {
    boolean pitchesDown = pitches(joint) && joint.axis().y() > 0;
    return pitchesDown ? Turning.CLOCKWISE : Turning.COUNTERCLOCKWISE;
  }

  /** Whether a joint turns about the robot's side-to-side axis, either way. */
  private static boolean pitches(Joint joint) {
    Vector3 axis = joint.axis();
    return joint instanceof Joint.Revolute && Math.abs(axis.y()) > 0.9;
  }

  // ---- Its named places and outputs ----

  private static List<Tick> ticks(
      RobotPlan.OfJoint planned, Drawn drawn, Display display, String goalName) {
    Joint joint = planned.joint();
    List<Tick> ticks = new ArrayList<>();
    if (drawn != Drawn.DIAL) {
      for (Position position : joint.positions()) {
        ticks.add(
            new Tick(
                position.name(),
                display.number(position.value()),
                joint.shown(position.value()),
                asked(position.name(), goalName)));
      }
      return ticks;
    }
    for (Output output : joint.outputs()) {
      ticks.add(
          new Tick(
              output.name(),
              display.number(speedOf(planned, output)),
              shown(joint, output),
              asked(output.name(), goalName)));
    }
    return ticks;
  }

  private static Asked asked(String name, String goalName) {
    return name.equals(goalName) ? Asked.ASKED_FOR : Asked.NOT_ASKED_FOR;
  }

  /**
   * The speed a named output runs its joint at, for its place on the speedometer: the speed itself,
   * or for a voltage or a duty cycle the speed it would run the joint at with nothing loading it.
   */
  private static double speedOf(RobotPlan.OfJoint planned, Output output) {
    if (asked(output) instanceof JointAsk.AtOutput atOutput) {
      return atOutput.unloadedSpeed(topSpeed(planned));
    }
    return 0;
  }

  /** A named output's value as text: "2000 rpm", "-4 V", "-30%". */
  private static String shown(Joint joint, Output output) {
    return switch (output.kind()) {
      case VELOCITY -> speedText(joint, output.value());
      case VOLTAGE -> Rules.shown(output.value()) + " V";
      case DUTY_CYCLE -> Rules.shown(output.value() * 100) + "%";
    };
  }

  // ---- The keep-outs on it, and the conditions of others' interlocks that are about it ----

  private static List<Band> bands(RobotPlan.OfJoint planned, Display display, Causes causes) {
    List<Band> bands = new ArrayList<>();
    for (Interlock interlock : planned.interlocks()) {
      if (interlock instanceof Interlock.KeepsOut keepsOut) {
        Range region = keepsOut.region();
        bands.add(
            new Band(
                display.number(region.lower()),
                display.number(region.upper()),
                display.text(region.lower()) + " to " + display.text(region.upper()),
                causes.emphasis(interlock),
                causes.reading(interlock),
                interlock.toString(),
                dependsOn(interlock.condition())));
      }
    }
    return bands;
  }

  /** What a condition is about: a joint's label, or a sensor's. */
  static String dependsOn(Condition condition) {
    return switch (condition) {
      case Condition.OnJoint onJoint -> onJoint.joint().label();
      case Condition.UpToSpeed upToSpeed -> upToSpeed.joint().label();
      case Condition.OnSensor onSensor -> Parts.labelOf(onSensor.sensor());
    };
  }

  private static List<Mark> marks(
      RobotPlan.OfJoint planned, Drawn drawn, Display display, Causes causes) {
    List<Mark> marks = new ArrayList<>();
    for (Interlock interlock : planned.dependents()) {
      double[] holdsOver = holdsOver(interlock.condition(), drawn);
      marks.add(
          new Mark(
              display.number(holdsOver[0]),
              display.number(holdsOver[1]),
              ruleOf(interlock),
              interlock.joint().label(),
              label(interlock),
              interlock.toString(),
              causes.emphasis(interlock),
              causes.reading(interlock)));
    }
    return marks;
  }

  static Rule ruleOf(Interlock interlock) {
    return switch (interlock) {
      case Interlock.RunsOnlyWhen runsOnlyWhen -> Rule.RUNS_ONLY_WHEN;
      case Interlock.KeepsOut keepsOut -> Rule.KEEPS_OUT;
    };
  }

  /**
   * A mark in a few words, on the card of what its condition is about: "roller runs only when at
   * least 110°", "arm keeps out while at most 0.8128 m". The condition's own words, without the
   * name of the joint or sensor whose card it is on.
   */
  static String label(Interlock interlock) {
    String condition = interlock.condition().toString();
    String about = dependsOn(interlock.condition());
    String withoutItsSubject =
        condition.startsWith(about + " ") ? condition.substring(about.length() + 1) : condition;
    return switch (interlock) {
      case Interlock.RunsOnlyWhen runsOnlyWhen ->
          interlock.joint().label() + " runs only when " + withoutItsSubject;
      case Interlock.KeepsOut keepsOut ->
          interlock.joint().label() + " keeps out while " + withoutItsSubject;
    };
  }

  /**
   * Where along a joint's drawing a condition about it holds, as a low end and a high end in the
   * joint's units: NaN for an end that is open. A condition on a speed is drawn on a speedometer
   * only, and one on a place on the other drawings only; elsewhere both ends are NaN, and the mark
   * is told in words alone.
   */
  private static double[] holdsOver(Condition condition, Drawn drawn) {
    double open = Double.NaN;
    boolean onASpeedometer = drawn == Drawn.DIAL;
    return switch (condition) {
      case Condition.AtLeast atLeast when !onASpeedometer ->
          new double[] {atLeast.position(), open};
      case Condition.AtMost atMost when !onASpeedometer -> new double[] {open, atMost.position()};
      case Condition.Between between when !onASpeedometer ->
          new double[] {
            Math.min(between.from(), between.to()), Math.max(between.from(), between.to())
          };
      case Condition.At at when !onASpeedometer && at.position().isPresent() ->
          new double[] {at.position().get().value(), at.position().get().value()};
      case Condition.UpToSpeed upToSpeed when onASpeedometer ->
          new double[] {upToSpeed.speed(), open};
      default -> new double[] {open, open};
    };
  }

  // ---- Where it is, where it was told to go, and where its profile has it ----

  private static Needles needles(
      RobotPlan.OfJoint planned, Drawn drawn, Display display, Moment.OfJoint now) {
    boolean idle = now.owner() == JointOwner.IDLE;
    if (drawn != Drawn.DIAL) {
      // An idle joint's profile is not running, so where it has the joint says nothing.
      return new Needles(
          display.number(now.position()),
          display.number(now.goal()),
          idle ? Double.NaN : display.number(now.setpoint()));
    }
    return new Needles(
        display.number(now.velocity()), display.number(speedAsked(planned, now)), Double.NaN);
  }

  /**
   * The speed a joint run at speeds was asked for, or NaN when nothing asked it to run. An output
   * asked for by name is drawn where its tick is.
   */
  private static double speedAsked(RobotPlan.OfJoint planned, Moment.OfJoint now) {
    if (now.owner() == JointOwner.IDLE) {
      return Double.NaN;
    }
    Output named = outputNamed(planned.joint(), now.goalName());
    if (named != null) {
      return speedOf(planned, named);
    }
    return now.goal();
  }

  static @Nullable Position placeNamed(Joint joint, String name) {
    for (Position position : joint.positions()) {
      if (position.name().equals(name)) {
        return position;
      }
    }
    return null;
  }

  static @Nullable Output outputNamed(Joint joint, String name) {
    for (Output output : joint.outputs()) {
      if (output.name().equals(name)) {
        return output;
      }
    }
    return null;
  }

  /** Running at a named output, as an ask of the joint, which says how fast it then runs. */
  private static JointAsk asked(Output output) {
    if (!output.runs()) {
      return JointAsk.NEUTRAL;
    }
    return switch (output.kind()) {
      case VELOCITY -> new JointAsk.AtVelocity(output.value());
      case VOLTAGE -> new JointAsk.AtVoltage(output.value());
      case DUTY_CYCLE -> new JointAsk.AtDutyCycle(output.value());
    };
  }

  // ---- The same in words: every sentence the model has a function for is the model's ----

  private static Facts facts(
      RobotPlan plan, RobotPlan.OfJoint planned, Drawn drawn, Moment moment, Moment.OfJoint now) {
    RobotDefinition definition = plan.definition();
    List<String> heldBy = new ArrayList<>();
    for (Holder holder : now.heldBy()) {
      heldBy.add(JointSentences.heldBy(definition, List.of(holder)));
    }
    Moment.LastMove last = now.lastMove();
    String lastMove =
        last.hasEnded()
            ? MoveSentences.outcome(
                definition, last.name(), last.parts(), last.heldAsItEnded(), last.why())
            : "none yet";
    return new Facts(
        at(planned.joint(), drawn, now),
        told(planned, drawn, now),
        ownedBy(planned.joint(), moment, now),
        lastMove,
        JointSentences.state(definition, planned.joint(), now.state(), now.fault()),
        now.state().standing(),
        hold(now),
        heldBy);
  }

  /**
   * Whether anything holds a joint. An interlock holds a joint whether or not anything asks the
   * joint to move, so a joint at rest is only restricted; it is held once a command, or code
   * outside any command, asks something of it.
   */
  private static Hold hold(Moment.OfJoint now) {
    if (now.heldBy().isEmpty()) {
      return Hold.FREE;
    }
    return now.owner() == JointOwner.IDLE ? Hold.RESTRICTED : Hold.HELD;
  }

  /**
   * Where a joint is: its position and its place among its named places, or how fast it runs and
   * the named output it runs at. The place is the robot's own fact, in the model's words.
   */
  private static String at(Joint joint, Drawn drawn, Moment.OfJoint now) {
    JointPlace place = now.at();
    String inWords = JointSentences.place(joint, place, now.position());
    if (drawn == Drawn.DIAL) {
      String speed = Double.isFinite(now.velocity()) ? speedText(joint, now.velocity()) : "unknown";
      return place.where() == JointPlace.Where.RUNNING ? speed + ", " + inWords : speed;
    }
    boolean isOnlyAPlace =
        place.where() == JointPlace.Where.UNKNOWN
            || place.where() == JointPlace.Where.UNNAMED
            || !Double.isFinite(now.position());
    if (isOnlyAPlace) {
      return inWords;
    }
    return joint.shown(now.position()) + ", " + inWords;
  }

  /** Where a joint was told to go, or what it was told to run at. */
  static String told(RobotPlan.OfJoint planned, Drawn drawn, Moment.OfJoint now) {
    Joint joint = planned.joint();
    boolean asked = now.owner() != JointOwner.IDLE;
    if (drawn == Drawn.DIAL) {
      Output named = outputNamed(joint, now.goalName());
      if (named != null) {
        return named.name() + ", " + shown(joint, named);
      }
      return asked && Double.isFinite(now.goal()) ? speedText(joint, now.goal()) : "nothing";
    }
    if (!Double.isFinite(now.goal())) {
      return "nothing";
    }
    String headedFor = joint.shown(now.goal());
    Position named = placeNamed(joint, now.goalName());
    if (named == null) {
      return asked ? headedFor : "hold at " + headedFor;
    }
    String place = named.name() + ", " + joint.shown(named.value());
    boolean headedThere = joint.shown(named.value()).equals(headedFor);
    return headedThere ? place : place + ", headed for " + headedFor;
  }

  /**
   * Who asked the joint in this loop, in the model's words, and for an idle joint what it does
   * idle. A joint that holds positions holds one; any other is at neutral, where its motors brake
   * or coast as its control says.
   */
  static String ownedBy(Joint joint, Moment moment, Moment.OfJoint now) {
    String owner = JointSentences.owner(now.owner(), now.command());
    if (now.owner() != JointOwner.IDLE) {
      return owner;
    }
    if (moment.enabled() == Moment.Enabled.DISABLED) {
      return owner + ", robot disabled";
    }
    if (joint.holdsPositions()) {
      return owner + ", holding";
    }
    boolean coasts =
        joint
            .driven()
            .control()
            .map(control -> control.neutralMode() == MechanismControl.NeutralMode.COAST)
            .orElse(false);
    return coasts ? owner + ", coasting" : owner + ", stopped";
  }

  // ---- Its own sensors ----

  private static List<OwnSensor> ownSensors(
      RobotPlan plan, Joint joint, Moment moment, Moment.OfJoint now) {
    List<OwnSensor> sensors = new ArrayList<>();
    sensors.add(
        switch (now.link()) {
          case ANSWERS -> new OwnSensor("motor controller", "answers", Health.FINE);
          case STALE ->
              new OwnSensor(
                  "motor controller", "connected, reading not fresh", Health.NEEDS_ATTENTION);
          case LOST -> new OwnSensor("motor controller", "lost", Health.NEEDS_ATTENTION);
        });
    if (joint.driven().absoluteEncoder().isPresent()) {
      sensors.add(
          switch (now.encoder()) {
            case HEALTHY -> new OwnSensor("absolute encoder", "healthy", Health.FINE);
            case MAGNET_BAD ->
                new OwnSensor("absolute encoder", "magnet reads badly", Health.NEEDS_ATTENTION);
            case LOST -> new OwnSensor("absolute encoder", "lost", Health.NEEDS_ATTENTION);
          });
    }
    for (Sensor.Switch.Limit.End end : Sensor.Switch.Limit.End.values()) {
      Sensor.Switch limit = plan.definition().math().limitSwitch(joint, end);
      if (limit != null) {
        sensors.add(limitSwitch(limit, moment.sensor(limit.label())));
      }
    }
    return sensors;
  }

  private static OwnSensor limitSwitch(Sensor.Switch limit, Moment.@Nullable OfSensor reading) {
    if (reading == null) {
      return new OwnSensor(limit.label(), "not in the record", Health.NEEDS_ATTENTION);
    }
    if (reading.link() == Moment.Link.LOST) {
      return new OwnSensor(limit.label(), "lost", Health.NEEDS_ATTENTION);
    }
    boolean tripped = reading.switched() == Moment.Switched.TRIPPED;
    return new OwnSensor(limit.label(), tripped ? "tripped" : "clear", Health.FINE);
  }
}
