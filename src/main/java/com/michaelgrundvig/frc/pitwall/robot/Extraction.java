package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.record.BindingFired;
import com.michaelgrundvig.frc.world.record.GateTerm;
import com.michaelgrundvig.frc.world.record.Holder;
import com.michaelgrundvig.frc.world.record.Identity;
import com.michaelgrundvig.frc.world.record.JointOwner;
import com.michaelgrundvig.frc.world.record.JointPlace;
import com.michaelgrundvig.frc.world.record.JointState;
import com.michaelgrundvig.frc.world.record.MoveOutcome;
import java.util.ArrayList;
import java.util.List;

/**
 * Takes a loop's {@link Moment} out of its record and puts it in the session memory. It runs once a
 * loop on the thread that hands the record over, AdvantageKit's receiver thread, and never on the
 * robot loop.
 *
 * <p>The keys it reads are worked out once, from the robot's joints and sensors, so a loop builds
 * no text. The gates are the one thing the model does not list: they are found in the record by
 * their keys, and the keys are walked only in a loop whose record has more keys than the last.
 *
 * <p>Not thread-safe: one thread takes the loops.
 */
final class Extraction {
  /** One joint's keys. */
  private record JointKeys(
      String connected,
      String fresh,
      String position,
      String velocity,
      String appliedVolts,
      String magnetBad,
      String absoluteConnected,
      String state,
      String fault,
      String owner,
      String command,
      String goal,
      String goalName,
      String at,
      String heldBy,
      String setpoint,
      String setpointVelocity,
      String outcome,
      String outcomeName,
      String outcomeWhy,
      String outcomeHeldBy,
      String absoluteAt) {
    static JointKeys of(String label) {
      String in = LogKeys.joint(label);
      String out = LogKeys.jointOutputs(label);
      return new JointKeys(
          in + LogKeys.CONNECTED,
          in + LogKeys.FRESH,
          in + LogKeys.POSITION,
          in + LogKeys.VELOCITY,
          in + LogKeys.APPLIED_VOLTS,
          in + LogKeys.MAGNET_BAD,
          in + LogKeys.ABSOLUTE_CONNECTED,
          out + LogKeys.STATE,
          out + LogKeys.FAULT,
          out + LogKeys.OWNER,
          out + LogKeys.COMMAND,
          out + LogKeys.GOAL,
          out + LogKeys.GOAL_NAME,
          out + LogKeys.AT,
          out + LogKeys.HELD_BY,
          out + LogKeys.SETPOINT,
          out + LogKeys.SETPOINT_VELOCITY,
          out + LogKeys.OUTCOME,
          out + LogKeys.OUTCOME_NAME,
          out + LogKeys.OUTCOME_WHY,
          out + LogKeys.OUTCOME_HELD_BY,
          out + LogKeys.ABSOLUTE_AT);
    }
  }

  /** One sensor's keys. */
  private record SensorKeys(String connected, String tripped, String meters) {
    static SensorKeys of(String label) {
      String in = LogKeys.sensor(label);
      return new SensorKeys(
          in + LogKeys.SENSOR_CONNECTED, in + LogKeys.TRIPPED, in + LogKeys.METERS);
    }
  }

  /** One gamepad's keys. */
  private record GamepadKeys(String buttons, String axes, String povs) {
    static GamepadKeys of(int port) {
      String in = LogKeys.gamepad(port);
      return new GamepadKeys(in + LogKeys.BUTTONS, in + LogKeys.AXES, in + LogKeys.POVS);
    }
  }

  /** One camera's keys. */
  private record CameraKeys(String sightings, String numbers) {
    static CameraKeys of(String name) {
      String out = LogKeys.camera(name);
      return new CameraKeys(out + LogKeys.SIGHTINGS, out + LogKeys.SIGHTING_NUMBERS);
    }
  }

  /** One gate's keys, and the place the memory keeps it in. */
  private record GateKeys(int place, String holds, String blocking, String blockingText) {}

  /**
   * How the last move on one joint ended, as last taken: the log's own lists and texts, and the
   * number the memory knows them by. The log hands the same lists back until a move ends, so a loop
   * in which none did compares four references and makes nothing.
   */
  private static final class MoveTaken {
    private MoveOutcome[] parts = NO_PARTS;
    private Holder[] heldAsItEnded = NO_HOLDERS;
    private String name = "";
    private String why = "";
    private short number;

    boolean isStill(MoveOutcome[] parts, String name, String why, Holder[] heldAsItEnded) {
      return this.parts == parts
          && this.heldAsItEnded == heldAsItEnded
          && this.name.equals(name)
          && this.why.equals(why);
    }
  }

  private static final MoveOutcome[] NO_PARTS = new MoveOutcome[0];
  private static final Holder[] NO_HOLDERS = new Holder[0];
  private static final GateTerm[] NO_TERMS = new GateTerm[0];
  private static final BindingFired[] NONE_FIRED = new BindingFired[0];
  private static final Sighting[] NONE_SIGHTED = new Sighting[0];

  /** The most numbers one key of the world's is read as: a camera's tags of one loop, at most. */
  private static final int MOST_NUMBERS = 32 * Sighting.NUMBERS;

  private final SessionMemory memory;
  private final RobotEvents events;
  private final List<JointKeys> joints = new ArrayList<>();
  private final List<SensorKeys> sensors = new ArrayList<>();
  private final List<GamepadKeys> gamepads = new ArrayList<>();
  private final List<CameraKeys> cameras = new ArrayList<>();
  private final List<GateKeys> gates = new ArrayList<>();

  /** Where a key's numbers are read into: used every loop, for one key after another. */
  private final double[] numbers = new double[MOST_NUMBERS];

  private final List<MoveTaken> moves = new ArrayList<>();

  /** The bindings that fired, each as the robot's list of its bindings says it: used every loop. */
  private final String[] firedSentences = new String[SessionMemory.FIRED];

  /** The names of the gates found so far, kept or not, so that each is looked at once. */
  private final List<String> gatesSeen = new ArrayList<>();

  /** How many keys the last record had: a record with more may have a gate that is new. */
  private int keysLastLoop;

  /** How many gates the record has that the memory has no place for; read from other threads. */
  private volatile int gatesNotKept;

  /**
   * @param events the session's events, which are found in each loop as it is taken
   * @param jointLabels the robot's joints, in the order the memory keeps them
   * @param sensorLabels the robot's switches and rangefinders, in the order the memory keeps them
   * @param cameraNames the robot's cameras, in the order the memory keeps them
   */
  Extraction(
      SessionMemory memory,
      RobotEvents events,
      List<String> jointLabels,
      List<String> sensorLabels,
      List<String> cameraNames) {
    this.memory = memory;
    this.events = events;
    jointLabels.forEach(label -> joints.add(JointKeys.of(label)));
    jointLabels.forEach(label -> moves.add(new MoveTaken()));
    sensorLabels.forEach(label -> sensors.add(SensorKeys.of(label)));
    cameraNames.forEach(name -> cameras.add(CameraKeys.of(name)));
    for (int port = 0; port < SessionMemory.GAMEPADS; port++) {
      gamepads.add(GamepadKeys.of(port));
    }
  }

  /** Takes one loop, and finds its events. */
  void take(LoopRecord record) {
    findNewGates(record);
    synchronized (memory) {
      memory.open(record.timestampNanos(), enabled(record), mode(record));
      for (int joint = 0; joint < joints.size(); joint++) {
        takeJoint(record, joint, joints.get(joint));
      }
      for (int sensor = 0; sensor < sensors.size(); sensor++) {
        takeSensor(record, sensor, sensors.get(sensor));
      }
      for (int gate = 0; gate < gates.size(); gate++) {
        GateKeys keys = gates.get(gate);
        if (record.has(keys.holds())) {
          Moment.Held held =
              record.flag(keys.holds(), false) ? Moment.Held.HOLDS : Moment.Held.DOES_NOT_HOLD;
          memory.putGate(
              keys.place(),
              held,
              record.facts(keys.blocking(), NO_TERMS),
              record.texts(keys.blockingText()));
        }
      }
      takeFired(record);
      for (int gamepad = 0; gamepad < gamepads.size(); gamepad++) {
        GamepadKeys keys = gamepads.get(gamepad);
        long[] povs = record.wholes(keys.povs());
        int pov = povs.length > 0 ? (int) povs[0] : -1;
        memory.putGamepad(
            gamepad, record.whole(keys.buttons(), 0), record.floats(keys.axes()), pov);
      }
      takeTheWorld(record);
      memory.close();
      events.findIn(memory.taken() - 1);
    }
  }

  /**
   * Takes where the robot is: the estimate with how sure it is and what it rests on, the truth in
   * the simulator, the wheels, and each camera's tags of this loop. A pose and a list of
   * measurements change every loop, so each is read as the numbers it is made of, into an array
   * kept for it, and nothing is made.
   */
  private void takeTheWorld(LoopRecord record) {
    WorldMemory world = memory.world();
    int slot = memory.slot();
    if (record.numbers(LogKeys.ESTIMATED_POSE, numbers) >= WorldMemory.POSE_NUMBERS) {
      world.putPose(slot, numbers);
    }
    if (record.numbers(LogKeys.COVARIANCE, numbers) >= WorldMemory.SPREAD_NUMBERS) {
      world.putSpread(slot, numbers);
    }
    world.putRests(slot, rests(record.text(LogKeys.RESTS_ON, "")));
    if (record.has(LogKeys.MEASUREMENTS_ACCEPTED)) {
      world.putTally(
          slot,
          record.whole(LogKeys.MEASUREMENTS_ACCEPTED, 0),
          record.whole(LogKeys.MEASUREMENTS_REJECTED, 0),
          record.number(LogKeys.VISION_AGE, Double.POSITIVE_INFINITY));
    }
    world.putCounts(
        slot,
        record.whole(LogKeys.RECOVERIES, Identity.NONE),
        record.whole(LogKeys.PLACEMENTS, 0),
        memory.word(record.text(LogKeys.PLACED_BECAUSE, "")));
    int moduleNumbers = Math.min(record.numbers(LogKeys.MODULES, numbers), numbers.length);
    world.putModules(slot, numbers, moduleNumbers / 2);
    if (record.numbers(LogKeys.TRUE_POSE, numbers) >= WorldMemory.POSE_NUMBERS) {
      world.putTruth(slot, numbers);
    }
    for (int camera = 0; camera < cameras.size(); camera++) {
      CameraKeys keys = cameras.get(camera);
      int measured = Math.min(record.numbers(keys.numbers(), numbers), numbers.length);
      if (measured == 0) {
        continue;
      }
      Sighting[] sighted = record.facts(keys.sightings(), NONE_SIGHTED);
      int howMany = Math.min(sighted.length, measured / Sighting.NUMBERS);
      world.putSightings(slot, camera, sighted, numbers, howMany);
    }
  }

  /** What the estimate rests on, from its name as localization logs it. */
  private static Moment.Rests rests(String name) {
    return switch (name) {
      case "INITIALIZING" -> Moment.Rests.INITIALIZING;
      case "TRACKING" -> Moment.Rests.TRACKING;
      case "DEAD_RECKONING" -> Moment.Rests.DEAD_RECKONING;
      case "LOST" -> Moment.Rests.LOST;
      case "IMU_FAULT" -> Moment.Rests.IMU_FAULT;
      default -> Moment.Rests.NOT_REPORTED;
    };
  }

  private static Moment.Enabled enabled(LoopRecord record) {
    return record.flag(LogKeys.ENABLED, false) ? Moment.Enabled.ENABLED : Moment.Enabled.DISABLED;
  }

  /** The mode, from its name as the Driver Station's record says it. */
  private static Moment.Mode mode(LoopRecord record) {
    return switch (record.text(LogKeys.ROBOT_MODE, "")) {
      case "AUTONOMOUS" -> Moment.Mode.AUTONOMOUS;
      case "TELEOPERATED" -> Moment.Mode.TELEOPERATED;
      case "UTILITY" -> Moment.Mode.UTILITY;
      default -> Moment.Mode.UNKNOWN;
    };
  }

  /** Takes a joint the record has: one the robot program drives. */
  private void takeJoint(LoopRecord record, int joint, JointKeys keys) {
    if (!record.has(keys.connected())) {
      return;
    }
    memory.putJoint(
        joint,
        link(record.flag(keys.connected(), false), record.flag(keys.fresh(), true)),
        record.number(keys.position(), Double.NaN),
        record.number(keys.velocity(), Double.NaN),
        record.number(keys.goal(), Double.NaN),
        record.number(keys.appliedVolts(), Double.NaN),
        record.number(keys.setpoint(), Double.NaN),
        record.number(keys.setpointVelocity(), Double.NaN),
        record.number(keys.absoluteAt(), Double.NaN),
        encoder(record, keys));
    memory.putJointFacts(
        joint,
        record.fact(keys.state(), JointState.UNHOMED),
        record.text(keys.fault(), ""),
        owner(record.text(keys.owner(), "")),
        record.text(keys.command(), ""),
        record.text(keys.goalName(), ""),
        record.fact(keys.at(), JointPlace.UNKNOWN),
        record.facts(keys.heldBy(), NO_HOLDERS));
    memory.putJointsLastMove(joint, lastMove(record, moves.get(joint), keys));
  }

  /** Who asked a joint, from the name the log holds; nothing, for a name that is not one. */
  private static JointOwner owner(String name) {
    return switch (name) {
      case "COMMAND" -> JointOwner.COMMAND;
      case "OUTSIDE_ANY_COMMAND" -> JointOwner.OUTSIDE_ANY_COMMAND;
      default -> JointOwner.IDLE;
    };
  }

  /**
   * The number the memory knows a joint's last move by. The move is looked up in the memory only in
   * the loop it changed, which is the loop a move on the joint ended.
   */
  private short lastMove(LoopRecord record, MoveTaken taken, JointKeys keys) {
    MoveOutcome[] parts = record.facts(keys.outcome(), NO_PARTS);
    Holder[] heldAsItEnded = record.facts(keys.outcomeHeldBy(), NO_HOLDERS);
    String name = record.text(keys.outcomeName(), "");
    String why = record.text(keys.outcomeWhy(), "");
    if (taken.isStill(parts, name, why, heldAsItEnded)) {
      return taken.number;
    }
    taken.parts = parts;
    taken.heldAsItEnded = heldAsItEnded;
    taken.name = name;
    taken.why = why;
    taken.number =
        memory.move(new Moment.LastMove(name, List.of(parts), why, List.of(heldAsItEnded)));
    return taken.number;
  }

  /**
   * Takes the bindings that fired in this loop, each as its sentence in the robot's list of its
   * bindings, which a fired binding points into, with the control on the gamepad that fired it.
   */
  private void takeFired(LoopRecord record) {
    BindingFired[] fired = record.facts(LogKeys.FIRED, NONE_FIRED);
    if (fired.length == 0) {
      return;
    }
    String[] bindings = record.texts(LogKeys.BINDINGS);
    int taken = Math.min(fired.length, firedSentences.length);
    for (int each = 0; each < taken; each++) {
      int binding = fired[each].binding();
      firedSentences[each] = binding >= 0 && binding < bindings.length ? bindings[binding] : "";
    }
    memory.putFired(firedSentences, fired, taken);
  }

  private static Moment.Link link(boolean connected, boolean fresh) {
    if (!connected) {
      return Moment.Link.LOST;
    }
    return fresh ? Moment.Link.ANSWERS : Moment.Link.STALE;
  }

  /**
   * How a joint's absolute encoder is doing. One the robot reads itself logs whether it answered;
   * one its motor controller reads says only whether its magnet reads badly.
   */
  private static Moment.Encoder encoder(LoopRecord record, JointKeys keys) {
    if (record.has(keys.absoluteConnected()) && !record.flag(keys.absoluteConnected(), true)) {
      return Moment.Encoder.LOST;
    }
    if (record.flag(keys.magnetBad(), false)) {
      return Moment.Encoder.MAGNET_BAD;
    }
    return Moment.Encoder.HEALTHY;
  }

  private void takeSensor(LoopRecord record, int sensor, SensorKeys keys) {
    if (!record.has(keys.connected())) {
      return;
    }
    memory.putSensor(
        sensor,
        record.flag(keys.connected(), false) ? Moment.Link.ANSWERS : Moment.Link.LOST,
        record.flag(keys.tripped(), false) ? Moment.Switched.TRIPPED : Moment.Switched.CLEAR,
        record.number(keys.meters(), Double.NaN));
  }

  /**
   * Finds the gates the record has that are new. A gate logs whether it holds under its name, and
   * its blocking terms under that. The record's keys are walked only when it has more of them than
   * the last loop's did, which is so in the first loops and hardly ever after.
   */
  private void findNewGates(LoopRecord record) {
    int keys = record.keyCount();
    if (keys == keysLastLoop) {
      return;
    }
    keysLastLoop = keys;
    record.forEachKey(
        key -> {
          boolean isAGatesOwn =
              key.startsWith(LogKeys.GATES)
                  && !key.endsWith(LogKeys.BLOCKING)
                  && !key.endsWith(LogKeys.BLOCKING_TEXT);
          if (!isAGatesOwn) {
            return;
          }
          String name = key.substring(LogKeys.GATES.length());
          if (gatesSeen.contains(name)) {
            return;
          }
          gatesSeen.add(name);
          int place;
          synchronized (memory) {
            place = memory.gate(name);
          }
          if (place >= 0) {
            gates.add(
                new GateKeys(place, key, key + LogKeys.BLOCKING, key + LogKeys.BLOCKING_TEXT));
          } else {
            gatesNotKept = gatesSeen.size() - gates.size();
          }
        });
  }

  /** How many gates the record has that the memory has no place for. */
  int gatesNotKept() {
    return gatesNotKept;
  }
}
