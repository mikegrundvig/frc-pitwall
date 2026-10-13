package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.record.BindingFired;
import com.michaelgrundvig.frc.world.record.BindingStart;
import com.michaelgrundvig.frc.world.record.GateTerm;
import com.michaelgrundvig.frc.world.record.Holder;
import com.michaelgrundvig.frc.world.record.MoveOutcome;

/**
 * A moment built by hand ({@link Moments}), written as the loop's record the runtime would have
 * logged for it, so a session takes a sequence of moments a test made the way it takes a robot's.
 */
final class Recorded {
  private Recorded() {}

  /** How long a loop of the test robot lasts, in nanoseconds: 20 ms. */
  static final long LOOP_NANOS = 20_000_000L;

  /**
   * Has a session take a moment as its next loop, 20 ms after its last.
   *
   * @return the index of the loop it was taken as
   */
  static long take(RobotSession session, Moment moment) {
    long index = session.taken();
    session.take(record(moment, index * LOOP_NANOS));
    return index;
  }

  /** The record of a loop that was a moment, at a time on the robot's clock. */
  static FakeRecord record(Moment moment, long timestampNanos) {
    FakeRecord record = new FakeRecord().at(timestampNanos);
    record
        .put(LogKeys.ENABLED, moment.enabled() == Moment.Enabled.ENABLED)
        .put(LogKeys.ROBOT_MODE, moment.mode().name());
    for (Moment.OfJoint joint : moment.joints()) {
      putJoint(record, joint);
    }
    for (Moment.OfSensor sensor : moment.sensors()) {
      record.sensor(
          sensor.label(),
          sensor.link() != Moment.Link.LOST,
          sensor.switched() == Moment.Switched.TRIPPED,
          sensor.meters());
    }
    for (Moment.OfGate gate : moment.gates()) {
      String key = LogKeys.GATES + gate.name();
      record
          .put(key, gate.held() == Moment.Held.HOLDS)
          .put(key + LogKeys.BLOCKING, gate.blocking().toArray(new GateTerm[0]))
          .put(key + LogKeys.BLOCKING_TEXT, gate.blockingText().toArray(new String[0]));
    }
    putFired(record, moment);
    for (Moment.OfGamepad gamepad : moment.gamepads()) {
      float[] axes = new float[gamepad.axes().size()];
      for (int axis = 0; axis < axes.length; axis++) {
        axes[axis] = gamepad.axes().get(axis).floatValue();
      }
      String in = LogKeys.gamepad(gamepad.port());
      record
          .put(in + LogKeys.BUTTONS, gamepad.buttons())
          .put(in + LogKeys.AXES, axes)
          .put(in + LogKeys.POVS, new long[] {gamepad.pov()});
    }
    return record;
  }

  /** The bindings that fired, each a fact that points into the robot's list of its bindings. */
  private static void putFired(FakeRecord record, Moment moment) {
    BindingFired[] fired = new BindingFired[moment.fired().size()];
    String[] bindings = new String[fired.length];
    for (int each = 0; each < fired.length; each++) {
      Moment.Fired one = moment.fired().get(each);
      bindings[each] = one.binding();
      fired[each] =
          new BindingFired(each, one.port(), one.control(), one.button(), BindingStart.WHILE_HELD);
    }
    record.put(LogKeys.BINDINGS, bindings).put(LogKeys.FIRED, fired);
  }

  private static void putJoint(FakeRecord record, Moment.OfJoint joint) {
    String in = LogKeys.joint(joint.label());
    String out = LogKeys.jointOutputs(joint.label());
    Moment.LastMove last = joint.lastMove();
    record
        .put(in + LogKeys.CONNECTED, joint.link() != Moment.Link.LOST)
        .put(in + LogKeys.FRESH, joint.link() == Moment.Link.ANSWERS)
        .put(in + LogKeys.POSITION, joint.position())
        .put(in + LogKeys.VELOCITY, joint.velocity())
        .put(in + LogKeys.APPLIED_VOLTS, joint.appliedVolts())
        .put(in + LogKeys.MAGNET_BAD, joint.encoder() == Moment.Encoder.MAGNET_BAD)
        .put(out + LogKeys.STATE, joint.state())
        .put(out + LogKeys.FAULT, joint.fault())
        .put(out + LogKeys.OWNER, joint.owner().name())
        .put(out + LogKeys.COMMAND, joint.command())
        .put(out + LogKeys.GOAL, joint.goal())
        .put(out + LogKeys.GOAL_NAME, joint.goalName())
        .put(out + LogKeys.AT, joint.at())
        .put(out + LogKeys.HELD_BY, joint.heldBy().toArray(new Holder[0]))
        .put(out + LogKeys.SETPOINT, joint.setpoint())
        .put(out + LogKeys.SETPOINT_VELOCITY, joint.setpointVelocity())
        .put(out + LogKeys.OUTCOME, last.parts().toArray(new MoveOutcome[0]))
        .put(out + LogKeys.OUTCOME_NAME, last.name())
        .put(out + LogKeys.OUTCOME_WHY, last.why())
        .put(out + LogKeys.OUTCOME_HELD_BY, last.heldAsItEnded().toArray(new Holder[0]));
    if (joint.encoder() == Moment.Encoder.LOST) {
      record.put(in + LogKeys.ABSOLUTE_CONNECTED, false);
    }
  }
}
