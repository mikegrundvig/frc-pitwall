package com.michaelgrundvig.frc.pitwall.robot;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.pitwall.AdvantageKitLog;
import java.lang.management.ManagementFactory;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.littletonrobotics.junction.LogDataReceiver;
import org.littletonrobotics.junction.LogTable;

/**
 * AdvantageKit's own table of a loop, taken into the session through the receiver a robot program
 * registers. The table is filled as the runtime fills it for the test robot: six joints, two
 * sensors, four gates and two gamepads.
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

  private final RobotSession session = RobotSession.of(TestRobot.definition());
  private final LogDataReceiver receiver = AdvantageKitLog.receiver(session);

  /** The table AdvantageKit hands over: the root's, whose keys are the log's own names. */
  private static LogTable loop(long micros, double pivotRadians, boolean triggerDown) {
    LogTable table = new LogTable(micros);
    table.put(LogKeys.ENABLED.substring(1), true);
    table.put(LogKeys.ROBOT_MODE.substring(1), "TELEOPERATED");
    for (String label : JOINTS) {
      String in = LogKeys.joint(label).substring(1);
      String out = LogKeys.jointOutputs(label).substring(1);
      table.put(in + LogKeys.CONNECTED, true);
      table.put(in + LogKeys.FRESH, true);
      table.put(in + LogKeys.POSITION, label.equals("pivot") ? pivotRadians : 0.0);
      table.put(in + LogKeys.VELOCITY, 0.0);
      table.put(in + LogKeys.APPLIED_VOLTS, 0.0);
      table.put(in + LogKeys.MAGNET_BAD, false);
      table.put(out + LogKeys.STATE, "READY");
      table.put(out + LogKeys.COMMAND, "idle");
      table.put(out + LogKeys.GOAL, 0.0);
      table.put(out + LogKeys.GOAL_NAME, "");
      table.put(out + LogKeys.SETPOINT, 0.0);
      table.put(out + LogKeys.SETPOINT_VELOCITY, 0.0);
      table.put(out + LogKeys.OUTCOME, "");
    }
    for (String label : new String[] {"claw beam", "hopper"}) {
      String in = LogKeys.sensor(label).substring(1);
      table.put(in + LogKeys.SENSOR_CONNECTED, true);
      table.put(in + LogKeys.TRIPPED, false);
      table.put(in + LogKeys.METERS, 0.8);
    }
    for (String gate : new String[] {"piece in", "aimed", "shooter at goal", "ready to feed"}) {
      table.put(LogKeys.GATES.substring(1) + gate, false);
      table.put(LogKeys.GATES.substring(1) + gate + LogKeys.BLOCKING, new String[] {"not yet"});
    }
    table.put(LogKeys.FIRED.substring(1), new String[0]);
    for (int port = 0; port < 2; port++) {
      String in = LogKeys.gamepad(port).substring(1);
      table.put(in + LogKeys.BUTTONS, triggerDown && port == 1 ? 1 << 10 : 0);
      table.put(in + LogKeys.AXES, new float[] {0, 0, 0, 0, 0, triggerDown ? 1 : 0});
      table.put(in + LogKeys.POVS, new int[] {-1});
    }
    return table;
  }

  /** Each of the table's types is read as the session's moment holds it. */
  @Test
  void aTablesValuesReachTheSessionsMoment() throws InterruptedException {
    receiver.putTable(loop(1_234_000_000L, 0.75, true));

    Moment moment = session.newest().orElseThrow();
    assertThat(moment.timestampNanos()).isEqualTo(1_234_000_000L);
    assertThat(moment.enabled()).isEqualTo(Moment.Enabled.ENABLED);
    assertThat(moment.mode()).isEqualTo(Moment.Mode.TELEOPERATED);
    assertThat(moment.joints()).hasSize(6);
    Moment.OfJoint pivot = Objects.requireNonNull(moment.joint("pivot"));
    assertThat(pivot.position()).isEqualTo(0.75);
    assertThat(pivot.state()).isEqualTo("READY");
    assertThat(pivot.link()).isEqualTo(Moment.Link.ANSWERS);
    assertThat(moment.sensors()).hasSize(2);
    assertThat(moment.gates()).hasSize(4);
    assertThat(moment.gates().get(0).blocking()).containsExactly("not yet");
    assertThat(moment.gamepads().get(1).isDown(10)).isTrue();
    assertThat(moment.gamepads().get(1).axes().get(5)).isEqualTo(1.0);
    assertThat(moment.gamepads().get(0).pov()).isEqualTo(-1);
  }

  /**
   * Taking a loop makes nothing new on the receiver's thread once the robot runs, so the session
   * costs the robot's garbage collector nothing however long it runs.
   */
  @Test
  void takingALoopAllocatesNothingOnceTheRobotRuns() throws InterruptedException {
    LogTable[] loops = new LogTable[64];
    for (int each = 0; each < loops.length; each++) {
      loops[each] = loop(each * 20_000_000L, each * 0.01, each % 2 == 0);
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
        "Taking a loop of a six-joint robot: %.2f µs and %.1f bytes a loop; %d bytes kept a"
            + " loop, %.1f MB for %d loops%n",
        nanos / 1e3 / measured,
        (double) bytes / measured,
        session.bytesPerMoment(),
        session.bytes() / 1e6,
        session.capacity());
    assertThat(bytes / measured).isLessThanOrEqualTo(MOST_BYTES_A_LOOP);
  }
}
