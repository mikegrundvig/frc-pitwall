package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.record.MoveResult;
import java.util.List;
import java.util.Optional;

/**
 * One thing that happened in the robot's session and is worth a mark on its timeline: in one loop,
 * something a person follows changed from what it was the loop before.
 *
 * @param number which event this is, counted from 0 at the session's first: it never repeats, so a
 *     page that holds the events up to one number asks only for those after it
 * @param loop the index of the loop it happened in
 * @param timestampNanos when that loop was, on the robot's clock, in nanoseconds
 * @param kind what happened
 * @param subject what kind of thing it is about
 * @param about which ones, by their names: a joint's label (every joint a move moved, for a move
 *     that ended), a sensor's label, a gate's name, the port of the gamepad a binding is on ("0"
 *     for the driver's, "1" for the operator's); empty for the robot itself
 * @param severity whether it is a problem, to be seen at a glance
 * @param sentence what happened, in words: the model's own sentence of the fact as it then stood,
 *     after the name of what it is about
 * @param moveResult how the move ended, for a move that ended; empty for every other kind
 */
public record RobotEvent(
    long number,
    long loop,
    long timestampNanos,
    Kind kind,
    Subject subject,
    List<String> about,
    Severity severity,
    String sentence,
    Optional<MoveResult> moveResult) {
  public RobotEvent {
    about = List.copyOf(about);
  }

  /** What happened. */
  public enum Kind {
    /** The robot was enabled. */
    ENABLED,
    /** The robot was disabled. */
    DISABLED,
    /**
     * The Driver Station chose another mode, and the robot stayed enabled or disabled as it was.
     */
    MODE_CHANGED,
    /** A press or a release started a binding's command. */
    BINDING_FIRED,
    /** A sensor that answered stopped answering. */
    SENSOR_LOST,
    /** A sensor that was lost answered again. */
    SENSOR_ANSWERED,
    /** A switch tripped. */
    SENSOR_TRIPPED,
    /** A switch cleared. */
    SENSOR_CLEARED,
    /** A gate that did not hold began to. */
    GATE_HELD,
    /** A gate that held stopped holding. */
    GATE_FELL,
    /** A joint's motor controller stopped answering. */
    JOINT_STOPPED_ANSWERING,
    /** A joint's motor controller answered again. */
    JOINT_ANSWERED,
    /** A joint's absolute encoder began to read badly, was lost, or was healthy again. */
    ENCODER_CHANGED,
    /** A joint's state changed: its homing began, it faulted, it became ready. */
    STATE_CHANGED,
    /** A move on a joint ended: there is a new outcome. */
    MOVE_ENDED,
    /** A command, or code outside any command, took a joint: its owner or its command changed. */
    JOINT_TAKEN,
    /** What owned a joint left it idle. */
    JOINT_RELEASED,
    /** A joint was asked for something else by name: another place, or another output. */
    JOINT_ASKED,
    /**
     * Something began to hold a joint short of what it was asked, or what holds it changed. An
     * interlock that restricts a joint nothing asks to move is no event.
     */
    HOLD_BEGAN,
    /** Nothing holds a joint short of what it is asked any more. */
    HOLD_ENDED
  }

  /** What kind of thing an event is about. */
  public enum Subject {
    ROBOT,
    GAMEPAD,
    SENSOR,
    GATE,
    JOINT
  }

  /** Whether an event is a problem. */
  public enum Severity {
    /** Something that happens when the robot works. */
    ROUTINE,
    /**
     * A move that timed out or was refused, a joint that faulted or stopped answering, a sensor or
     * an encoder that was lost: what a person looking for a failure is looking for.
     */
    PROBLEM
  }

  /** Whether the event is about something with a name: a joint, a sensor, a gate or a port. */
  public boolean isAbout(String name) {
    return about.contains(name);
  }
}
