package com.michaelgrundvig.frc.pitwall;

import com.michaelgrundvig.frc.pitwall.experiment.ExperimentInputs;
import com.michaelgrundvig.frc.pitwall.robot.LoopRecord;
import com.michaelgrundvig.frc.pitwall.robot.RobotSession;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.littletonrobotics.junction.LogDataReceiver;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.inputs.LoggableInputs;

/**
 * Everything of Pitwall's that names AdvantageKit, so that a robot program without AdvantageKit
 * never loads a class that does.
 *
 * <p>Pitwall's requests are AdvantageKit inputs, recorded as they happen and read back in their
 * place during a replay, so the replay makes the same requests at the same moments. Those parts are
 * loaded only when the robot program has AdvantageKit ({@link Pitwall} checks first).
 *
 * <p>And the log reaches Pitwall through here: {@link #receiver} is what a robot program hands
 * AdvantageKit so that every loop's record is taken into the robot's session, which the page's
 * Robot view is drawn from. Only a program that has AdvantageKit calls it.
 */
public final class AdvantageKitLog {
  private AdvantageKitLog() {}

  /**
   * What takes each loop's record into a robot's session. Hand it to AdvantageKit ({@code
   * Logger.addDataReceiver}) before the log starts, beside the log's file and its stream.
   *
   * <p>AdvantageKit hands it a copy of the whole record at the end of every loop, on a thread of
   * its own and never the robot loop's. Taking a loop reads a few dozen values out of the record
   * and makes nothing new.
   */
  public static LogDataReceiver receiver(RobotSession session) {
    return new SessionReceiver(session);
  }

  /**
   * AdvantageKit's record of one loop, read as the session reads it. There is one of these for the
   * session, pointed at each loop's table in turn, so that none is made each loop.
   *
   * <p>A value is looked up in the table's own map of every key, by the key as the log names it.
   * The table's {@code get} would put its prefix before the key first, which builds a text for
   * every value read. And each value is read with what to answer otherwise, because AdvantageKit's
   * readers that take none make an empty array to answer with, every time.
   */
  private static final class SessionReceiver implements LogDataReceiver, LoopRecord {
    /**
     * A fact as it was last read out of the log: the log's value it was made from, and the fact.
     *
     * @param from the value in the log, which stays the same object from loop to loop until the
     *     robot writes the key again
     * @param fact the record, or the array of records, made from it
     */
    private record Read(LogTable.LogValue from, Object fact) {}

    private final RobotSession session;
    private long timestampNanos;
    private LogTable table = new LogTable(0);
    private Map<String, LogTable.LogValue> values = Map.of();

    /**
     * Each fact as last read, by its key. The robot writes a fact only when it changes, and making
     * a record out of the log's bytes makes new objects, so a fact is made again only when the
     * log's value is a new one.
     */
    private final Map<String, Read> facts = new HashMap<>();

    SessionReceiver(RobotSession session) {
      this.session = session;
    }

    @Override
    public void putTable(LogTable table) {
      timestampNanos = table.getTimestamp();
      this.table = table;
      values = table.getAll(false);
      session.take(this);
    }

    @Override
    public long timestampNanos() {
      return timestampNanos;
    }

    @Override
    public boolean has(String key) {
      return values.containsKey(key);
    }

    @Override
    public boolean flag(String key, boolean otherwise) {
      LogTable.LogValue value = values.get(key);
      if (value == null || value.type != LogTable.LoggableType.Boolean) {
        return otherwise;
      }
      return value.getBoolean(otherwise);
    }

    @Override
    public double number(String key, double otherwise) {
      LogTable.LogValue value = values.get(key);
      if (value == null) {
        return otherwise;
      }
      return switch (value.type) {
        case Double -> value.getDouble(otherwise);
        case Float -> value.getFloat((float) otherwise);
        case Integer -> value.getInteger(0);
        default -> otherwise;
      };
    }

    @Override
    public long whole(String key, long otherwise) {
      LogTable.LogValue value = values.get(key);
      if (value == null || value.type != LogTable.LoggableType.Integer) {
        return otherwise;
      }
      return value.getInteger(otherwise);
    }

    @Override
    public String text(String key, String otherwise) {
      LogTable.LogValue value = values.get(key);
      if (value == null || value.type != LogTable.LoggableType.String) {
        return otherwise;
      }
      return value.getString(otherwise);
    }

    @Override
    public String[] texts(String key) {
      LogTable.LogValue value = values.get(key);
      if (value == null || value.type != LogTable.LoggableType.StringArray) {
        return NO_TEXTS;
      }
      return value.getStringArray(NO_TEXTS);
    }

    @Override
    public float[] floats(String key) {
      LogTable.LogValue value = values.get(key);
      if (value == null || value.type != LogTable.LoggableType.FloatArray) {
        return NO_FLOATS;
      }
      return value.getFloatArray(NO_FLOATS);
    }

    @Override
    public long[] wholes(String key) {
      LogTable.LogValue value = values.get(key);
      if (value == null || value.type != LogTable.LoggableType.IntegerArray) {
        return NO_WHOLES;
      }
      return value.getIntegerArray(NO_WHOLES);
    }

    @Override
    public <R extends Record> R fact(String key, R otherwise) {
      LogTable.LogValue value = values.get(key);
      if (value == null) {
        return otherwise;
      }
      Read read = facts.get(key);
      if (read == null || read.from() != value) {
        // The table reads by a key without its first slash, under its own prefix, which is one.
        read = new Read(value, table.get(key.substring(1), otherwise));
        facts.put(key, read);
      }
      @SuppressWarnings("unchecked") // the fact was made as what is asked for here
      R fact = (R) read.fact();
      return fact;
    }

    @Override
    public <R extends Record> R[] facts(String key, R[] otherwise) {
      LogTable.LogValue value = values.get(key);
      if (value == null) {
        return otherwise;
      }
      Read read = facts.get(key);
      if (read == null || read.from() != value) {
        read = new Read(value, table.get(key.substring(1), otherwise));
        facts.put(key, read);
      }
      @SuppressWarnings("unchecked") // the facts were made as what is asked for here
      R[] made = (R[]) read.fact();
      return made;
    }

    @Override
    public int keyCount() {
      return values.size();
    }

    @Override
    public void forEachKey(Consumer<String> each) {
      values.keySet().forEach(each);
    }
  }

  /** Whether AdvantageKit is replaying a log, when the page must not start. */
  static boolean replaying() {
    return Logger.hasReplaySource();
  }

  static void recordOutput(String key, String[] value) {
    Logger.recordOutput(key, value);
  }

  /** Ends the log, writing what's left, before the program stops. */
  static void end() {
    Logger.end();
  }

  static Consumer<TuningInputs> tuning() {
    return new TuningLog();
  }

  static Consumer<ActionInputs> actions() {
    return new ActionLog();
  }

  static Consumer<ExperimentInputs> experiments() {
    return new ExperimentLog();
  }

  /** Says how a run ended, in the log beside its requests. */
  static void runEnded(String what) {
    Logger.recordOutput("Pitwall/Experiments/Ended", what);
  }

  static Consumer<TunableInputs> tunables() {
    return new TunableLog();
  }

  static final class TuningLog implements Consumer<TuningInputs>, LoggableInputs {
    private TuningInputs inputs = new TuningInputs();

    @Override
    public void accept(TuningInputs inputs) {
      this.inputs = inputs;
      Logger.processInputs("Pitwall/Tuning", this);
    }

    @Override
    public void toLog(LogTable table) {
      table.put("Enabled", inputs.enabled);
      table.put("Kinds", inputs.kinds);
    }

    @Override
    public void fromLog(LogTable table) {
      inputs.enabled = table.get("Enabled", inputs.enabled);
      inputs.kinds = table.get("Kinds", inputs.kinds);
    }
  }

  static final class TunableLog implements Consumer<TunableInputs>, LoggableInputs {
    private TunableInputs inputs = new TunableInputs();

    @Override
    public void accept(TunableInputs inputs) {
      this.inputs = inputs;
      Logger.processInputs("Pitwall/Tunables", this);
    }

    @Override
    public void toLog(LogTable table) {
      table.put("Paths", inputs.paths);
      table.put("Values", inputs.values);
    }

    @Override
    public void fromLog(LogTable table) {
      inputs.paths = table.get("Paths", inputs.paths);
      inputs.values = table.get("Values", inputs.values);
    }
  }

  static final class ExperimentLog implements Consumer<ExperimentInputs>, LoggableInputs {
    private ExperimentInputs inputs = new ExperimentInputs();

    @Override
    public void accept(ExperimentInputs inputs) {
      this.inputs = inputs;
      Logger.processInputs("Pitwall/Experiments", this);
    }

    @Override
    public void toLog(LogTable table) {
      table.put("Kinds", inputs.kinds);
      table.put("Names", inputs.names);
      table.put("Runs", inputs.runs);
    }

    @Override
    public void fromLog(LogTable table) {
      inputs.kinds = table.get("Kinds", inputs.kinds);
      inputs.names = table.get("Names", inputs.names);
      inputs.runs = table.get("Runs", inputs.runs);
    }
  }

  static final class ActionLog implements Consumer<ActionInputs>, LoggableInputs {
    private ActionInputs inputs = new ActionInputs();

    @Override
    public void accept(ActionInputs inputs) {
      this.inputs = inputs;
      Logger.processInputs("Pitwall/Actions", this);
    }

    @Override
    public void toLog(LogTable table) {
      table.put("Kinds", inputs.kinds);
      table.put("Names", inputs.names);
    }

    @Override
    public void fromLog(LogTable table) {
      inputs.kinds = table.get("Kinds", inputs.kinds);
      inputs.names = table.get("Names", inputs.names);
    }
  }
}
