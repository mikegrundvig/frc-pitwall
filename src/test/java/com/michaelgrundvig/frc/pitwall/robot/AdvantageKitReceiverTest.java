package com.michaelgrundvig.frc.pitwall.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import com.michaelgrundvig.frc.pitwall.AdvantageKitLog;
import com.michaelgrundvig.frc.world.record.BindingFired;
import com.michaelgrundvig.frc.world.record.BindingStart;
import com.michaelgrundvig.frc.world.record.GateTerm;
import com.michaelgrundvig.frc.world.record.Holder;
import com.michaelgrundvig.frc.world.record.Identity;
import com.michaelgrundvig.frc.world.record.JointOwner;
import com.michaelgrundvig.frc.world.record.JointPlace;
import com.michaelgrundvig.frc.world.record.JointState;
import com.michaelgrundvig.frc.world.record.MoveOutcome;
import com.michaelgrundvig.frc.world.record.MoveProgress;
import com.michaelgrundvig.frc.world.record.MoveReason;
import com.michaelgrundvig.frc.world.record.MoveResult;
import com.michaelgrundvig.frc.world.record.MoveTarget;
import com.michaelgrundvig.frc.world.record.MoveTask;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.littletonrobotics.junction.LogDataReceiver;
import org.littletonrobotics.junction.LogTable;
import org.wpilib.math.geometry.Pose3d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.geometry.Rotation3d;
import org.wpilib.math.kinematics.SwerveModuleVelocity;

/**
 * AdvantageKit's own table of a loop, taken into the session through the receiver a robot program
 * registers. The table is filled as the runtime fills it for the test robot: six joints, two
 * sensors, four gates and two gamepads, with every fact a record of the model's.
 */
class AdvantageKitReceiverTest {
  private static final com.sun.management.ThreadMXBean THREADS =
      (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

  /**
   * The most a loop may allocate on the receiver's thread, on average, once the robot runs: nothing
   * at all, with room for what the JVM itself allocates while it measures.
   */
  private static final long MOST_BYTES_A_LOOP = 16;

  private static final String[] JOINTS = {"pivot", "roller", "elevator", "arm", "shooter", "claw"};
  private static final String[] CAMERAS = {"front", "on-arm"};

  /** How sure a pose is, as the runtime logs it: a record of four numbers. */
  private record Covariance(double xx, double xy, double yy, double heading) {}

  private final RobotSession session = RobotSession.of(TestRobot.definition());
  private final LogDataReceiver receiver = AdvantageKitLog.receiver(session);

  /**
   * The log as the robot fills it in one loop: every key the session reads, with each fact a
   * record, as the runtime logs them. What changes every loop is put by {@link #loop}.
   */
  private static LogTable everything() {
    LogTable table = new LogTable(0);
    table.put(LogKeys.ENABLED.substring(1), true);
    table.put(LogKeys.ROBOT_MODE.substring(1), "TELEOPERATED");
    for (String label : JOINTS) {
      String in = LogKeys.joint(label).substring(1);
      String out = LogKeys.jointOutputs(label).substring(1);
      table.put(in + LogKeys.CONNECTED, true);
      table.put(in + LogKeys.FRESH, true);
      table.put(in + LogKeys.POSITION, 0.0);
      table.put(in + LogKeys.VELOCITY, 0.0);
      table.put(in + LogKeys.APPLIED_VOLTS, 0.0);
      table.put(in + LogKeys.MAGNET_BAD, false);
      table.put(out + LogKeys.STATE, JointState.READY);
      table.put(out + LogKeys.FAULT, "");
      table.put(out + LogKeys.OWNER, JointOwner.COMMAND);
      table.put(out + LogKeys.COMMAND, "Intake");
      table.put(out + LogKeys.GOAL, 0.0);
      table.put(out + LogKeys.GOAL_NAME, "");
      table.put(out + LogKeys.AT, JointPlace.between(0, 1));
      table.put(out + LogKeys.HELD_BY, new Holder[] {Holder.interlock(0)});
      table.put(out + LogKeys.SETPOINT, 0.0);
      table.put(out + LogKeys.SETPOINT_VELOCITY, 0.0);
      table.put(out + LogKeys.OUTCOME, new MoveOutcome[] {A_MOVE});
      table.put(out + LogKeys.OUTCOME_NAME, "pivot → out");
      table.put(out + LogKeys.OUTCOME_WHY, "");
      table.put(out + LogKeys.OUTCOME_HELD_BY, new Holder[] {Holder.interlock(0)});
    }
    for (String label : new String[] {"claw beam", "hopper"}) {
      String in = LogKeys.sensor(label).substring(1);
      table.put(in + LogKeys.SENSOR_CONNECTED, true);
      table.put(in + LogKeys.TRIPPED, false);
      table.put(in + LogKeys.METERS, 0.8);
    }
    for (String gate : new String[] {"piece in", "aimed", "shooter at goal", "ready to feed"}) {
      String key = LogKeys.GATES.substring(1) + gate;
      table.put(key, false);
      table.put(key + LogKeys.BLOCKING, new GateTerm[] {GateTerm.text(0), GateTerm.settling()});
      table.put(key + LogKeys.BLOCKING_TEXT, new String[] {"not yet"});
    }
    table.put(LogKeys.RESTS_ON.substring(1), "TRACKING");
    table.put(LogKeys.MEASUREMENTS_ACCEPTED.substring(1), 12);
    table.put(LogKeys.MEASUREMENTS_REJECTED.substring(1), 3);
    table.put(LogKeys.RECOVERIES.substring(1), 0);
    table.put(LogKeys.PLACEMENTS.substring(1), 1);
    table.put(LogKeys.PLACED_BECAUSE.substring(1), "placed on Center");
    for (String camera : CAMERAS) {
      table.put(
          LogKeys.camera(camera).substring(1) + LogKeys.SIGHTINGS,
          new Sighting[] {
            new Sighting(7, 0, Sighting.Fate.ACCEPTED, 0, 1),
            new Sighting(8, 0, Sighting.Fate.REJECTED, 1, 1)
          });
    }
    table.put(
        LogKeys.BINDINGS.substring(1), new String[] {"operator right trigger: while held: Intake"});
    table.put(
        LogKeys.FIRED.substring(1),
        new BindingFired[] {
          new BindingFired(
              0, 1, BindingFired.Control.RIGHT_TRIGGER, Identity.NONE, BindingStart.WHILE_HELD)
        });
    return table;
  }

  /**
   * One loop's table, as AdvantageKit hands it over: a copy of the log, in which whatever the robot
   * did not write again is the very value it was, and what it reads every loop is new.
   */
  private static LogTable loop(LogTable log, long nanos, double pivotRadians, boolean triggerDown) {
    LogTable table = LogTable.clone(log);
    table.setTimestamp(nanos);
    table.put(LogKeys.joint("pivot").substring(1) + LogKeys.POSITION, pivotRadians);
    // The robot drives: its pose, how sure it is, its wheels and its cameras' measurements are new
    // every loop, as structs and lists of numbers.
    double along = pivotRadians;
    Pose3d estimated =
        new Pose3d(3 + along, 4, 0.02, new Rotation3d(0.01, 0.02, Math.toRadians(30) + along));
    table.put(LogKeys.ESTIMATED_POSE.substring(1), estimated);
    table.put(
        LogKeys.TRUE_POSE.substring(1), new Pose3d(3.05 + along, 4, 0, new Rotation3d(0, 0, 0.5)));
    table.put(
        LogKeys.COVARIANCE.substring(1), new Covariance(0.0004 + along, 0.0001, 0.0009, 0.0001));
    table.put(LogKeys.VISION_AGE.substring(1), 0.1 + along);
    SwerveModuleVelocity[] modules = new SwerveModuleVelocity[4];
    for (int module = 0; module < modules.length; module++) {
      modules[module] = new SwerveModuleVelocity(1.5 + along, new Rotation2d(0.25 * module));
    }
    table.put(LogKeys.MODULES.substring(1), modules);
    for (String camera : CAMERAS) {
      double[] numbers = new double[2 * Sighting.NUMBERS];
      java.util.Arrays.fill(numbers, 1 + along);
      table.put(LogKeys.camera(camera).substring(1) + LogKeys.SIGHTING_NUMBERS, numbers);
    }
    for (int port = 0; port < 2; port++) {
      String in = LogKeys.gamepad(port).substring(1);
      table.put(in + LogKeys.BUTTONS, triggerDown && port == 1 ? 1 << 10 : 0);
      table.put(in + LogKeys.AXES, new float[] {0, 0, 0, 0, 0, triggerDown ? 1 : 0});
      table.put(in + LogKeys.POVS, new int[] {0});
    }
    return table;
  }

  private static final MoveOutcome A_MOVE =
      new MoveOutcome(
          MoveResult.TIMED_OUT,
          MoveTask.GO_TO,
          MoveProgress.BEGUN,
          1.62,
          0,
          Identity.NONE,
          MoveTarget.PLACE,
          1,
          2.27,
          0.03,
          -0.5,
          Double.NaN,
          MoveReason.NONE,
          Identity.NONE);

  /** Each of the table's types is read as the session's moment holds it. */
  @Test
  void aTablesValuesReachTheSessionsMoment() throws InterruptedException {
    receiver.putTable(loop(everything(), 1_234_000_000L, 0.75, true));

    Moment moment = session.newest().orElseThrow();
    assertThat(moment.timestampNanos()).isEqualTo(1_234_000_000L);
    assertThat(moment.enabled()).isEqualTo(Moment.Enabled.ENABLED);
    assertThat(moment.mode()).isEqualTo(Moment.Mode.TELEOPERATED);
    assertThat(moment.joints()).hasSize(6);
    Moment.OfJoint pivot = Objects.requireNonNull(moment.joint("pivot"));
    assertThat(pivot.position()).isEqualTo(0.75);
    assertThat(pivot.state()).isEqualTo(JointState.READY);
    assertThat(pivot.owner()).isEqualTo(JointOwner.COMMAND);
    assertThat(pivot.command()).isEqualTo("Intake");
    assertThat(pivot.at()).isEqualTo(JointPlace.between(0, 1));
    assertThat(pivot.heldBy()).containsExactly(Holder.interlock(0));
    assertThat(pivot.lastMove())
        .isEqualTo(
            new Moment.LastMove("pivot → out", List.of(A_MOVE), "", List.of(Holder.interlock(0))));
    assertThat(moment.fired())
        .as("a fired binding in its words, with the control on the gamepad that fired it")
        .containsExactly(
            new Moment.Fired(
                "operator right trigger: while held: Intake",
                1,
                BindingFired.Control.RIGHT_TRIGGER,
                Identity.NONE));
    assertThat(pivot.link()).isEqualTo(Moment.Link.ANSWERS);
    assertThat(moment.sensors()).hasSize(2);
    assertThat(moment.gates()).hasSize(4);
    assertThat(moment.gates().get(0))
        .isEqualTo(
            new Moment.OfGate(
                "piece in",
                Moment.Held.DOES_NOT_HOLD,
                List.of(GateTerm.text(0), GateTerm.settling()),
                List.of("not yet")));
    assertThat(moment.gamepads().get(1).isDown(10)).isTrue();
    assertThat(moment.gamepads().get(1).axes().get(5)).isEqualTo(1.0);
    assertThat(moment.gamepads().get(0).pov()).isEqualTo(0);
  }

  /** A pose, a covariance and the wheels are structs in the log, read as the numbers they are. */
  @Test
  void theWorldsStructsReachTheSessionsMoment() throws InterruptedException {
    receiver.putTable(loop(everything(), 1_234_000_000L, 0.75, false));

    Moment.OfWorld world = session.newest().orElseThrow().world();
    Moment.OfEstimate estimate = world.estimate().orElseThrow();
    assertThat(estimate.pose().getX()).isCloseTo(3.75, within(1e-6));
    assertThat(estimate.pose().getZ()).isCloseTo(0.02, within(1e-6));
    assertThat(estimate.pose().getRotation().getX()).isCloseTo(0.01, within(1e-6));
    assertThat(estimate.pose().getRotation().getY()).isCloseTo(0.02, within(1e-6));
    assertThat(estimate.pose().getRotation().getZ())
        .isCloseTo(Math.toRadians(30) + 0.75, within(1e-6));
    assertThat(estimate.spread().orElseThrow().xx()).isCloseTo(0.7504, within(1e-6));
    assertThat(estimate.spread().orElseThrow().yy()).isCloseTo(0.0009, within(1e-9));
    assertThat(estimate.rests()).isEqualTo(Moment.Rests.TRACKING);
    assertThat(estimate.tally().orElseThrow().accepted()).isEqualTo(12);
    assertThat(estimate.placedBecause()).isEqualTo("placed on Center");
    assertThat(world.truth().orElseThrow().getX()).isCloseTo(3.8, within(1e-6));
    assertThat(world.modules()).hasSize(4);
    assertThat(world.modules().get(3).metersPerSecond()).isCloseTo(2.25, within(1e-6));
    assertThat(world.modules().get(3).radians()).isCloseTo(0.75, within(1e-6));
    assertThat(world.cameras().get(1).sightings())
        .extracting(Moment.Sighted::tag, Moment.Sighted::fate)
        .containsExactly(tuple(8, Sighting.Fate.REJECTED), tuple(7, Sighting.Fate.ACCEPTED));
    assertThat(world.cameras().get(1).sightings().get(0).innovationMeters())
        .isCloseTo(1.75, within(1e-6));
  }

  /**
   * Taking a loop, and finding its events, makes nothing new on the receiver's thread once the
   * robot runs, so the session costs the robot's garbage collector nothing however long it runs.
   * The robot writes a fact only when it changes, and a fact is made out of the log's bytes only
   * then.
   */
  @Test
  void takingALoopAllocatesNothingOnceTheRobotRuns() throws InterruptedException {
    LogTable log = everything();
    LogTable[] loops = new LogTable[64];
    for (int each = 0; each < loops.length; each++) {
      loops[each] = loop(log, each * 20_000_000L, each * 0.01, each % 2 == 0);
    }
    for (int warm = 0; warm < 20_000; warm++) {
      receiver.putTable(loops[warm % loops.length]);
    }

    int measured = 20_000;
    long thread = Thread.currentThread().threadId();
    long before = THREADS.getThreadAllocatedBytes(thread);
    long started = System.nanoTime();
    for (int each = 0; each < measured; each++) {
      receiver.putTable(loops[each % loops.length]);
    }
    long nanos = System.nanoTime() - started;
    long bytes = THREADS.getThreadAllocatedBytes(thread) - before;

    System.out.printf(
        "Taking a loop of a six-joint robot with two cameras, and finding its events: %.2f µs and %.1f bytes a"
            + " loop; %d bytes kept a loop, %.1f MB for %d loops; %.1f KB more for %d events%n",
        nanos / 1e3 / measured,
        (double) bytes / measured,
        session.bytesPerMoment(),
        session.bytes() / 1e6,
        session.capacity(),
        session.events().bytes() / 1e3,
        session.events().capacity());
    assertThat(bytes / measured).isLessThanOrEqualTo(MOST_BYTES_A_LOOP);
    assertThat(session.events().found())
        .as("a binding fired in every loop, and each firing was found as an event")
        .isEqualTo(2L * measured - 1);
  }
}
