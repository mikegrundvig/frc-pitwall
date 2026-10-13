package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Kind;
import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Severity;
import com.michaelgrundvig.frc.world.record.JointOwner;
import com.michaelgrundvig.frc.world.record.JointState;
import com.michaelgrundvig.frc.world.record.MoveResult;

/**
 * Finds the events of one loop: what differs, among the facts a person follows, between a loop and
 * the loop before it. The events of a session are this, loop after loop, and nothing else, so they
 * are a function of the session's moments alone: found as each loop is taken, or found again later
 * from the loops the memory still holds, they are the same.
 *
 * <p>It reads the two loops where the session memory keeps them and makes nothing new, so a loop in
 * which nothing changed costs a few dozen comparisons on the thread that takes the loops.
 *
 * <p>In one loop the events are in the order a cause runs: the robot's mode, the bindings that
 * fired, the sensors, the gates, each joint in the model's order, and then where the robot is: its
 * placing, its cameras, and its estimate.
 */
final class EventFinder {
  private EventFinder() {}

  /**
   * Adds a loop's events to a ring. A loop whose loop before the memory does not hold has none: the
   * session's first, which has nothing to differ from.
   *
   * <p>Call it under the memory's lock.
   *
   * @param loop the index of the loop, which the memory holds
   */
  static void find(SessionMemory memory, long loop, EventRing into) {
    long before = loop - 1;
    if (before < memory.oldest()) {
      return;
    }
    findTheRobots(memory, before, loop, into);
    for (int fired = 0; fired < memory.firedIn(loop); fired++) {
      into.add(loop, Kind.BINDING_FIRED, fired, Severity.ROUTINE);
    }
    for (int sensor = 0; sensor < memory.sensors(); sensor++) {
      findASensors(memory, before, loop, sensor, into);
    }
    for (int gate = 0; gate < memory.gates(); gate++) {
      findAGates(memory, before, loop, gate, into);
    }
    for (int joint = 0; joint < memory.joints(); joint++) {
      findAJoints(memory, before, loop, joint, into);
    }
    findTheWorlds(memory, before, loop, into);
  }

  /**
   * How long a camera must go without a refused measurement before the next one is news, in loops:
   * about a second. A camera at the edge of what it can see is refused and accepted by turns, and a
   * run of that is one event, its first.
   */
  static final int REFUSALS_APART_LOOPS = 50;

  /**
   * Where the robot is: it was placed, a camera's measurement was refused, the estimate recovered,
   * or what it rests on changed.
   */
  private static void findTheWorlds(SessionMemory memory, long before, long loop, EventRing into) {
    WorldMemory world = memory.world();
    int now = memory.slotOf(loop);
    int was = memory.slotOf(before);
    boolean estimated = world.hasPose(now) && world.hasPose(was);
    if (estimated && world.placementsIn(now) > world.placementsIn(was)) {
      into.add(loop, Kind.ROBOT_PLACED, 0, Severity.ROUTINE);
    }
    for (int camera = 0; camera < world.cameras(); camera++) {
      if (world.firstRefusedIn(now, camera) >= 0 && beginsARun(memory, loop, camera)) {
        into.add(loop, Kind.SIGHTING_REFUSED, camera, Severity.ROUTINE);
      }
    }
    if (!estimated) {
      return;
    }
    boolean recoveriesCounted = world.recoveriesIn(was) >= 0;
    if (recoveriesCounted && world.recoveriesIn(now) > world.recoveriesIn(was)) {
      into.add(loop, Kind.ESTIMATE_RECOVERED, 0, Severity.PROBLEM);
    }
    Moment.Rests rests = world.restsIn(now);
    if (rests != world.restsIn(was)) {
      Severity severity = rests.isWrong() ? Severity.PROBLEM : Severity.ROUTINE;
      into.add(loop, Kind.ESTIMATE_RESTS_CHANGED, 0, severity);
    }
  }

  /**
   * Whether a camera's refused measurement in a loop is the first of a run: none of that camera's
   * was refused in the {@link #REFUSALS_APART_LOOPS} before it.
   */
  private static boolean beginsARun(SessionMemory memory, long loop, int camera) {
    long earliest = Math.max(memory.oldest(), loop - REFUSALS_APART_LOOPS);
    for (long earlier = loop - 1; earlier >= earliest; earlier--) {
      if (memory.world().firstRefusedIn(memory.slotOf(earlier), camera) >= 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Enabled, disabled, or another mode. Enabling and disabling say the mode, so they say it all.
   */
  private static void findTheRobots(SessionMemory memory, long before, long loop, EventRing into) {
    Moment.Enabled enabled = memory.enabledIn(loop);
    if (enabled != memory.enabledIn(before)) {
      Kind kind = enabled == Moment.Enabled.ENABLED ? Kind.ENABLED : Kind.DISABLED;
      into.add(loop, kind, 0, Severity.ROUTINE);
      return;
    }
    if (memory.modeIn(loop) != memory.modeIn(before)) {
      into.add(loop, Kind.MODE_CHANGED, 0, Severity.ROUTINE);
    }
  }

  private static void findASensors(
      SessionMemory memory, long before, long loop, int sensor, EventRing into) {
    if (!memory.hasSensor(loop, sensor) || !memory.hasSensor(before, sensor)) {
      return;
    }
    boolean answers = memory.sensorAnswers(loop, sensor);
    if (answers != memory.sensorAnswers(before, sensor)) {
      if (answers) {
        into.add(loop, Kind.SENSOR_ANSWERED, sensor, Severity.ROUTINE);
      } else {
        into.add(loop, Kind.SENSOR_LOST, sensor, Severity.PROBLEM);
      }
      return;
    }
    boolean tripped = memory.sensorTripped(loop, sensor);
    if (answers && tripped != memory.sensorTripped(before, sensor)) {
      Kind kind = tripped ? Kind.SENSOR_TRIPPED : Kind.SENSOR_CLEARED;
      into.add(loop, kind, sensor, Severity.ROUTINE);
    }
  }

  private static void findAGates(
      SessionMemory memory, long before, long loop, int gate, EventRing into) {
    if (!memory.hasGate(loop, gate) || !memory.hasGate(before, gate)) {
      return;
    }
    boolean holds = memory.gateHolds(loop, gate);
    if (holds != memory.gateHolds(before, gate)) {
      into.add(loop, holds ? Kind.GATE_HELD : Kind.GATE_FELL, gate, Severity.ROUTINE);
    }
  }

  /**
   * One joint's events, in the order they read: how its parts are, how the move on it ended, who
   * owns it now, what it was asked, and what holds it.
   */
  private static void findAJoints(
      SessionMemory memory, long before, long loop, int joint, EventRing into) {
    if (!memory.hasJoint(loop, joint) || !memory.hasJoint(before, joint)) {
      return;
    }
    boolean answers = memory.jointAnswers(loop, joint);
    if (answers != memory.jointAnswers(before, joint)) {
      if (answers) {
        into.add(loop, Kind.JOINT_ANSWERED, joint, Severity.ROUTINE);
      } else {
        into.add(loop, Kind.JOINT_STOPPED_ANSWERING, joint, Severity.PROBLEM);
      }
    }
    int encoder = memory.encoderBits(loop, joint);
    if (encoder != memory.encoderBits(before, joint)) {
      Severity severity = encoder == 0 ? Severity.ROUTINE : Severity.PROBLEM;
      into.add(loop, Kind.ENCODER_CHANGED, joint, severity);
    }
    if (memory.stateBits(loop, joint) != memory.stateBits(before, joint)) {
      boolean faulted = memory.standingIn(loop, joint) == JointState.Standing.FAULTED;
      Severity severity = faulted ? Severity.PROBLEM : Severity.ROUTINE;
      into.add(loop, Kind.STATE_CHANGED, joint, severity);
    }
    if (aMoveEndedFirstOn(memory, before, loop, joint)) {
      into.add(loop, Kind.MOVE_ENDED, joint, severityOf(memory, loop, joint));
    }
    JointOwner owner = memory.ownerIn(loop, joint);
    boolean ownerChanged =
        owner != memory.ownerIn(before, joint)
            || memory.commandNumber(loop, joint) != memory.commandNumber(before, joint);
    if (ownerChanged) {
      Kind kind = owner == JointOwner.IDLE ? Kind.JOINT_RELEASED : Kind.JOINT_TAKEN;
      into.add(loop, kind, joint, Severity.ROUTINE);
    }
    int goalName = memory.goalNameNumber(loop, joint);
    if (goalName != 0 && goalName != memory.goalNameNumber(before, joint)) {
      into.add(loop, Kind.JOINT_ASKED, joint, Severity.ROUTINE);
    }
    boolean held = isHeldShort(memory, loop, joint);
    boolean wasHeld = isHeldShort(memory, before, joint);
    if (held && (!wasHeld || !memory.sameHolders(loop, before, joint))) {
      into.add(loop, Kind.HOLD_BEGAN, joint, Severity.ROUTINE);
    }
    if (wasHeld && !held && owner != JointOwner.IDLE) {
      into.add(loop, Kind.HOLD_ENDED, joint, Severity.ROUTINE);
    }
  }

  /**
   * Whether something held a joint short of what it was asked. An interlock restricts a joint
   * whether or not anything asks the joint to move, and that alone is no event: a roller at rest
   * behind its interlock is the robot working. It holds the joint once a command asks.
   */
  private static boolean isHeldShort(SessionMemory memory, long loop, int joint) {
    return memory.isHeld(loop, joint) && memory.ownerIn(loop, joint) != JointOwner.IDLE;
  }

  /**
   * Whether a move ended on a joint in a loop, and this joint is the first of the joints it ended
   * on. A move of several joints, a configuration's, ends on each of them in one loop with one
   * outcome, and is one event, which is about all of them.
   */
  private static boolean aMoveEndedFirstOn(
      SessionMemory memory, long before, long loop, int joint) {
    short move = memory.lastMoveNumber(loop, joint);
    if (move == memory.lastMoveNumber(before, joint)) {
      return false;
    }
    for (int earlier = 0; earlier < joint; earlier++) {
      boolean endedThereToo =
          memory.hasJoint(loop, earlier)
              && memory.lastMoveNumber(loop, earlier) == move
              && memory.lastMoveNumber(before, earlier) != move;
      if (endedThereToo) {
        return false;
      }
    }
    return true;
  }

  /** A move that timed out or was refused is a problem; one that arrived or was canceled is not. */
  private static Severity severityOf(SessionMemory memory, long loop, int joint) {
    Moment.LastMove move = memory.moveOf(memory.lastMoveNumber(loop, joint));
    if (!move.hasEnded()) {
      return Severity.ROUTINE;
    }
    MoveResult result = move.parts().getFirst().result();
    boolean failed = result == MoveResult.TIMED_OUT || result == MoveResult.REFUSED;
    return failed ? Severity.PROBLEM : Severity.ROUTINE;
  }
}
