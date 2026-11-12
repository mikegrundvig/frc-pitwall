package com.michaelgrundvig.frc.pitwall;

import com.michaelgrundvig.frc.pitwall.experiment.ExperimentInputs;
import com.michaelgrundvig.frc.pitwall.robot.LoopRecord;
import com.michaelgrundvig.frc.pitwall.robot.RobotSession;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.littletonrobotics.junction.LogDataReceiver;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.inputs.LoggableInputs;
import org.wpilib.util.Alert;

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
    return receiver(session::take, AdvantageKitLog::alert);
  }

  /**
   * A receiver that hands each loop's record to {@code taking}, and stops taking any after the
   * first that throws, saying so once through {@code stopped}.
   */
  static LogDataReceiver receiver(Consumer<LoopRecord> taking, Consumer<String> stopped) {
    return new SessionReceiver(taking, stopped);
  }

  /** Raises a robot alert that stays up for the rest of the session. */
  private static void alert(String text) {
    // Never closed: the alert stays up until the program ends, as the session stays stopped.
    Alert alert = new Alert("Pitwall", text, Alert.Level.HIGH);
    alert.set(true);
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
    /** Whether it takes the loops, or stopped after one that threw. */
    private enum Taking {
      TAKING,
      STOPPED
    }

    private static final double[] NO_DOUBLES = new double[0];
    private static final byte[] NO_BYTES = new byte[0];

    /** Reads a number out of a struct's bytes, where WPILib's struct format puts it. */
    private static final VarHandle DOUBLES =
        MethodHandles.byteArrayViewVarHandle(double[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * A fact as it was last read out of the log: the log's value it was made from, and the fact.
     * The value in the log stays the same object from loop to loop until the robot writes the key
     * again. Written again with the same bytes, it is the same fact, and is not made again.
     */
    private static final class Read {
      private LogTable.LogValue from;
      private Object fact;

      Read(LogTable.LogValue from, Object fact) {
        this.from = from;
        this.fact = fact;
      }

      /** Whether a value in the log is the one this fact was made from, or says the same. */
      boolean isOf(LogTable.LogValue value) {
        if (from == value) {
          return true;
        }
        boolean same =
            value.type == LogTable.LoggableType.Raw
                && from.type == LogTable.LoggableType.Raw
                && Arrays.equals(value.getRaw(NO_BYTES), from.getRaw(NO_BYTES));
        if (same) {
          from = value;
        }
        return same;
      }
    }

    private final Consumer<LoopRecord> taking;
    private final Consumer<String> stopped;
    private Taking state = Taking.TAKING;
    private long timestampNanos;
    private LogTable table = new LogTable(0);
    private Map<String, LogTable.LogValue> values = Map.of();

    /**
     * Each fact as last read, by its key. The robot writes a fact only when it changes, and making
     * a record out of the log's bytes makes new objects, so a fact is made again only when the
     * log's value is a new one.
     */
    private final Map<String, Read> facts = new HashMap<>();

    SessionReceiver(Consumer<LoopRecord> taking, Consumer<String> stopped) {
      this.taking = taking;
      this.stopped = stopped;
    }

    /**
     * Takes one loop's record, and never throws. AdvantageKit calls every receiver in turn on one
     * thread and catches nothing: a throw here would end that thread, and with it the log's file
     * and its stream for the rest of the session. So a bug in Pitwall's reading costs only
     * Pitwall's Robot view, which stops at the loop before, and says so once.
     */
    @Override
    public void putTable(LogTable table) {
      if (state == Taking.STOPPED) {
        return;
      }
      try {
        timestampNanos = table.getTimestamp();
        this.table = table;
        values = table.getAll(false);
        taking.accept(this);
      } catch (RuntimeException thrown) {
        stop(thrown);
      }
    }

    private void stop(RuntimeException thrown) {
      state = Taking.STOPPED;
      String text =
          "Pitwall stopped reading the log: its Robot view ends here, and the log itself goes on. "
              + Said.of(thrown);
      // The stack, for whoever reads the console: the alert has room for one line.
      System.err.println(text);
      thrown.printStackTrace();
      try {
        stopped.accept(text);
      } catch (RuntimeException alsoThrown) {
        // Saying so failed too: the log still goes on.
        alsoThrown.printStackTrace();
      }
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
    public int numbers(String key, double[] into) {
      LogTable.LogValue value = values.get(key);
      if (value == null) {
        return 0;
      }
      if (value.type == LogTable.LoggableType.DoubleArray) {
        double[] held = value.getDoubleArray(NO_DOUBLES);
        System.arraycopy(held, 0, into, 0, Math.min(held.length, into.length));
        return held.length;
      }
      if (value.type != LogTable.LoggableType.Raw) {
        return 0;
      }
      // A struct made of numbers alone is those numbers one after another, eight bytes each, the
      // lowest byte first: WPILib's struct format. Read where they lie, nothing is made.
      byte[] bytes = value.getRaw(NO_BYTES);
      int held = bytes.length / Double.BYTES;
      for (int each = 0; each < held && each < into.length; each++) {
        into[each] = (double) DOUBLES.get(bytes, each * Double.BYTES);
      }
      return held;
    }

    @Override
    public <R extends Record> R fact(String key, R otherwise) {
      LogTable.LogValue value = values.get(key);
      if (value == null) {
        return otherwise;
      }
      Read read = facts.get(key);
      if (read == null || !read.isOf(value)) {
        // The table reads by a key without its first slash, under its own prefix, which is one.
        read = new Read(value, table.get(key.substring(1), otherwise));
        facts.put(key, read);
      }
      @SuppressWarnings("unchecked") // the fact was made as what is asked for here
      R fact = (R) read.fact;
      return fact;
    }

    @Override
    public <R extends Record> R[] facts(String key, R[] otherwise) {
      LogTable.LogValue value = values.get(key);
      if (value == null) {
        return otherwise;
      }
      Read read = facts.get(key);
      if (read == null || !read.isOf(value)) {
        read = new Read(value, table.get(key.substring(1), otherwise));
        facts.put(key, read);
      }
      @SuppressWarnings("unchecked") // the facts were made as what is asked for here
      R[] made = (R[]) read.fact;
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
