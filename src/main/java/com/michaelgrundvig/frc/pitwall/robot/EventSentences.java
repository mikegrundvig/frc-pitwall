package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Kind;
import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Severity;
import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Subject;
import com.michaelgrundvig.frc.world.record.Identity;
import com.michaelgrundvig.frc.world.record.JointOwner;
import com.michaelgrundvig.frc.world.record.JointSentences;
import com.michaelgrundvig.frc.world.record.MoveOutcome;
import com.michaelgrundvig.frc.world.record.MoveResult;
import com.michaelgrundvig.frc.world.record.MoveSentences;
import com.michaelgrundvig.frc.world.robot.Joint;
import com.michaelgrundvig.frc.world.robot.RobotDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Says an event in words, from the moment of the loop it happened in. An event is a fact that
 * changed, and its sentence is that fact as it then stood, said as the joint's card says it: by the
 * model's own function where the model has one ({@code MoveSentences}, {@code JointSentences},
 * {@code ControlSentences}), after the name of what it is about.
 */
final class EventSentences {
  private EventSentences() {}

  /** What an event is about, and what it says. */
  private record Said(
      Subject subject, List<String> about, String sentence, Optional<MoveResult> result) {
    static Said of(Subject subject, String about, String sentence) {
      return new Said(subject, List.of(about), sentence, Optional.empty());
    }
  }

  /**
   * One event, in words.
   *
   * @param moment the moment of the event's loop
   * @param about which joint, sensor, gate or binding it is about, by its place in the session
   *     memory: the place of a joint or a sensor in the plan's order, of a gate among the moment's,
   *     of a binding among those that fired in the loop
   * @param gate the gate's name, for an event about one
   */
  static RobotEvent event(
      RobotPlan plan,
      long number,
      long loop,
      Moment moment,
      Kind kind,
      int about,
      String gate,
      Severity severity) {
    Said said =
        switch (kind) {
          case ENABLED, DISABLED, MODE_CHANGED ->
              new Said(Subject.ROBOT, List.of(), ofTheRobot(kind, moment), Optional.empty());
          case BINDING_FIRED ->
              new Said(Subject.GAMEPAD, List.of(), firedBinding(moment, about), Optional.empty());
          case SENSOR_LOST, SENSOR_ANSWERED, SENSOR_TRIPPED, SENSOR_CLEARED ->
              ofASensor(plan, kind, about);
          case GATE_HELD, GATE_FELL -> ofAGate(plan.definition(), moment, gate);
          default -> ofAJoint(plan, moment, kind, about);
        };
    return new RobotEvent(
        number,
        loop,
        moment.timestampNanos(),
        kind,
        said.subject(),
        said.about(),
        severity,
        said.sentence(),
        said.result());
  }

  /** A binding that fired, as the robot's list of its bindings says it. */
  private static String firedBinding(Moment moment, int which) {
    return which < moment.fired().size() ? moment.fired().get(which) : "";
  }

  private static String ofTheRobot(Kind kind, Moment moment) {
    String mode = moment.mode() == Moment.Mode.UNKNOWN ? "no mode" : modeWords(moment.mode());
    return switch (kind) {
      case ENABLED -> "the robot was enabled in " + mode;
      case DISABLED -> MoveSentences.ROBOT_WAS_DISABLED;
      default -> "the mode changed to " + mode;
    };
  }

  private static String modeWords(Moment.Mode mode) {
    return mode == Moment.Mode.TELEOPERATED ? "teleop" : mode.name().toLowerCase(Locale.ROOT);
  }

  private static Said ofASensor(RobotPlan plan, Kind kind, int about) {
    String label = Parts.labelOf(plan.sensors().get(about).sensor());
    String reads =
        switch (kind) {
          case SENSOR_LOST -> "lost";
          case SENSOR_ANSWERED -> "answers again";
          case SENSOR_TRIPPED -> "tripped";
          default -> "clear";
        };
    return Said.of(Subject.SENSOR, label, label + " " + reads);
  }

  /** A gate that fell says why, in the model's words; one that holds says that it does. */
  private static Said ofAGate(RobotDefinition definition, Moment moment, String name) {
    for (Moment.OfGate gate : moment.gates()) {
      if (gate.name().equals(name)) {
        RobotView.GateRow row = RobotView.GateRow.of(definition, gate);
        boolean holds = gate.held() == Moment.Held.HOLDS;
        return Said.of(Subject.GATE, name, holds ? name + " holds" : row.why());
      }
    }
    return Said.of(Subject.GATE, name, name);
  }

  private static Said ofAJoint(RobotPlan plan, Moment moment, Kind kind, int about) {
    RobotPlan.OfJoint planned = plan.joints().get(about);
    Joint joint = planned.joint();
    String label = joint.label();
    Moment.OfJoint now = moment.joint(label);
    if (now == null) {
      return Said.of(Subject.JOINT, label, label);
    }
    if (kind == Kind.MOVE_ENDED) {
      return ofAMove(plan.definition(), label, now.lastMove());
    }
    RobotDefinition definition = plan.definition();
    String says =
        switch (kind) {
          case JOINT_STOPPED_ANSWERING -> MoveSentences.notAnswering(joint);
          case JOINT_ANSWERED -> label + " answers again";
          case ENCODER_CHANGED -> label + " absolute encoder " + encoderWords(now.encoder());
          case STATE_CHANGED ->
              label + " " + JointSentences.state(definition, joint, now.state(), now.fault());
          case JOINT_TAKEN -> label + " " + takenBy(now);
          case JOINT_RELEASED -> label + " " + JointCards.ownedBy(joint, moment, now);
          case JOINT_ASKED ->
              label + " told " + JointCards.told(planned, JointCards.drawn(joint), now);
          case HOLD_BEGAN -> label + " held: " + JointSentences.heldBy(definition, now.heldBy());
          default -> label + " no longer held";
        };
    return Said.of(Subject.JOINT, label, says);
  }

  /** Who took a joint: "owned by Intake", or "asked outside any command". */
  private static String takenBy(Moment.OfJoint now) {
    String owner = JointSentences.owner(now.owner(), now.command());
    return now.owner() == JointOwner.COMMAND ? "owned by " + owner : "asked " + owner;
  }

  private static String encoderWords(Moment.Encoder encoder) {
    return switch (encoder) {
      case HEALTHY -> "healthy";
      case MAGNET_BAD -> "magnet reads badly";
      case LOST -> "lost";
    };
  }

  /** A move that ended, in the model's sentence: it is about every joint the move moved. */
  private static Said ofAMove(RobotDefinition definition, String label, Moment.LastMove move) {
    if (!move.hasEnded()) {
      return Said.of(Subject.JOINT, label, label + ": a move ended");
    }
    List<String> about = new ArrayList<>();
    about.add(label);
    for (MoveOutcome part : move.parts()) {
      if (Identity.isSome(part.joint())) {
        String moved = definition.joints().get(part.joint()).label();
        if (!about.contains(moved)) {
          about.add(moved);
        }
      }
    }
    String sentence =
        MoveSentences.outcome(
            definition, move.name(), move.parts(), move.heldAsItEnded(), move.why());
    return new Said(Subject.JOINT, about, sentence, Optional.of(move.parts().getFirst().result()));
  }
}
