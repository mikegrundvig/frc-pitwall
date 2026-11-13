package com.michaelgrundvig.frc.pitwall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wpilib.tunable.MockTunableBackend;
import org.wpilib.tunable.Tunable;
import org.wpilib.tunable.TunableBackend;
import org.wpilib.tunable.TunableBase;
import org.wpilib.tunable.TunableBoolean;
import org.wpilib.tunable.TunableConfig;
import org.wpilib.tunable.TunableDouble;
import org.wpilib.tunable.TunableFloat;
import org.wpilib.tunable.TunableInt;
import org.wpilib.tunable.TunableLong;
import org.wpilib.tunable.TunableRegistry;

/**
 * The tee between WPILib's tunables and a dashboard, with WPILib's mock backend as the dashboard.
 */
class TunableTeeTest {
  private final MockTunableBackend dashboard = new MockTunableBackend();
  private final List<String> logged = new ArrayList<>();
  private final List<String> tuned = new ArrayList<>();
  private @Nullable String whyLocked;
  private final TunableTee tee =
      new TunableTee(
          dashboard,
          () -> whyLocked,
          inputs ->
              logged.add(String.join(",", inputs.paths) + "=" + String.join(",", inputs.values)),
          false);
  private final TunableDouble kP =
      TunableDouble.createConfig(0.5, new TunableConfig().withOnTune(() -> tuned.add("kP")));
  private final TunableBoolean intake = TunableBoolean.create(false);

  @BeforeEach
  void publish() {
    tee.publish("Shooter/kP", kP);
    tee.publish("Intake/enabled", intake);
  }

  @AfterEach
  void reset() {
    TunableRegistry.reset();
  }

  @Test
  void dashboardsSeeEveryTunableThroughTheTee() {
    assertThat(dashboard.getDouble("Shooter/kP")).isEqualTo(0.5);

    kP.set(0.7);

    assertThat(dashboard.getDouble("Shooter/kP")).as("the real value").isEqualTo(0.7);
  }

  @Test
  void aDashboardChangeAppliesWhileTuningIsAllowedAndIsLogged() {
    dashboard.setDouble("Shooter/kP", 0.9);
    tee.update();

    assertThat(kP.get()).isEqualTo(0.9);
    assertThat(logged).containsExactly("/Shooter/kP=0.9");
    assertThat(tuned).as("its onTune ran").containsExactly("kP");
  }

  @Test
  void aDashboardChangeWhileLockedIsRefused() {
    whyLocked = "the field system is connected";

    dashboard.setDouble("Shooter/kP", 0.9);
    dashboard.setBoolean("Intake/enabled", true);
    tee.update();

    assertThat(kP.get()).isEqualTo(0.5);
    assertThat(intake.get()).isFalse();
    assertThat(dashboard.getDouble("Shooter/kP")).as("snaps back").isEqualTo(0.5);
    assertThat(logged).containsExactly("=");
    assertThat(tuned).isEmpty();
  }

  @Test
  void replayMakesTheLoggedChangesAndIgnoresDashboards() {
    var replayDashboard = new MockTunableBackend();
    var replay =
        new TunableTee(
            replayDashboard,
            () -> null,
            inputs -> {
              inputs.paths = new String[] {"/Shooter/kP"};
              inputs.values = new String[] {"0.8"};
            },
            true);
    var gain =
        TunableDouble.createConfig(0.5, new TunableConfig().withOnTune(() -> tuned.add("gain")));
    replay.publish("Shooter/kP", gain);

    replayDashboard.setDouble("Shooter/kP", 0.9);
    replay.update();

    assertThat(gain.get()).isEqualTo(0.8);
    assertThat(tuned).containsExactly("gain");
  }

  @Test
  void thePageListsEveryTunableAndChangesSeveralAtOnce() {
    tee.update();
    assertThat(tee.view())
        .containsExactly(
            new TunableTee.Listed("/Shooter/kP", "double", 0.5, true, Limits.NONE),
            new TunableTee.Listed("/Intake/enabled", "boolean", false, true, Limits.NONE));

    Map<String, Object> values = new LinkedHashMap<>();
    values.put("Shooter/kP", 1.5);
    values.put("Intake/enabled", true);
    var outcome = tee.requestChanges(values);
    assertThat(outcome).as("waits for the update").isNotDone();
    tee.update();

    assertThat(outcome.getNow(null)).isEqualTo(RequestOutcome.applied());
    assertThat(kP.get()).isEqualTo(1.5);
    assertThat(intake.get()).isTrue();
    assertThat(logged).last().isEqualTo("/Shooter/kP,/Intake/enabled=1.5,true");
    assertThat(tee.isEditable("Shooter/kP")).isTrue();
  }

  @Test
  void thePagesRequestsWaitForTheNextUpdateAndTheTeeSaysHowMany() {
    assertThat(tee.waiting()).isZero();

    tee.requestChanges(Map.of("Shooter/kP", 1.5));
    tee.requestChanges(Map.of("Intake/enabled", true));

    assertThat(tee.waiting()).as("asked, and not yet applied").isEqualTo(2);
    assertThat(kP.get()).isEqualTo(0.5);

    tee.update();

    assertThat(tee.waiting()).isZero();
    assertThat(kP.get()).isEqualTo(1.5);
  }

  @Test
  void thePageIsLockedLikeDashboards() {
    whyLocked = "tuning is off";

    var outcome = tee.requestChanges(Map.of("Shooter/kP", 1.5));
    tee.update();

    assertThat(outcome.getNow(null))
        .isEqualTo(
            new RequestOutcome(RequestOutcome.Result.LOCKED, "Tuning is locked: tuning is off"));
    assertThat(kP.get()).isEqualTo(0.5);
  }

  @Test
  void aValueOfTheWrongTypeChangesNothing() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("Shooter/kP", 1.5);
    values.put("Intake/enabled", 3);
    var outcome = tee.requestChanges(values);
    tee.update();

    assertThat(outcome.getNow(null))
        .isEqualTo(
            RequestOutcome.refused(
                List.of(
                    RequestOutcome.Reason.of(
                        RequestOutcome.Layer.VALUE,
                        "/Intake/enabled",
                        "",
                        "/Intake/enabled takes a boolean value",
                        "/Intake/enabled takes a boolean value"))));
    assertThat(outcome.getNow(null).detail()).isEqualTo("/Intake/enabled takes a boolean value");
    assertThat(kP.get()).as("all or none").isEqualTo(0.5);
  }

  /** A switch left changed in the pit (a camera off, say) is listed, for the field's alert. */
  @Test
  void listsWhatChangedSinceStartup() {
    assertThat(tee.changedSinceStartup()).isEmpty();

    dashboard.setBoolean("Intake/enabled", true);
    tee.update();

    assertThat(tee.changedSinceStartup()).containsExactly("/Intake/enabled: true (was false)");
  }

  /** A value that isn't a number would throw wherever the robot program uses it. */
  @Test
  void numbersThatArentNumbersAreRefused() {
    var page = tee.requestChanges(Map.of("Shooter/kP", Double.POSITIVE_INFINITY));
    dashboard.setDouble("Shooter/kP", Double.NaN);
    tee.update();

    assertThat(page.getNow(null).detail()).isEqualTo("/Shooter/kP takes a double value");
    assertThat(page.getNow(null).reasons())
        .singleElement()
        .satisfies(reason -> {
          assertThat(reason.layer()).isEqualTo(RequestOutcome.Layer.VALUE);
          assertThat(reason.paths()).containsExactly("/Shooter/kP");
        });
    assertThat(kP.get()).isEqualTo(0.5);
    assertThat(dashboard.getDouble("Shooter/kP")).as("snaps back").isEqualTo(0.5);
  }

  @Test
  void aWholeNumberBeyondItsTypeIsRefused() {
    var count = TunableLong.create(3);
    tee.publish("Arm/count", count);

    var page = tee.requestChanges(Map.of("Arm/count", 1e30)); // would saturate to Long.MAX_VALUE
    tee.update();

    assertThat(page.getNow(null).result()).isEqualTo(RequestOutcome.Result.REFUSED);
    assertThat(count.get()).isEqualTo(3);
  }

  /** A team's tunable whose setter throws refuses the value; the page's other changes go back. */
  @Test
  void aSetterThatThrowsRefusesAndTheOtherChangesGoBack() {
    double[] limit = {10};
    var guarded =
        TunableDouble.createConfig(
            () -> limit[0],
            value -> {
              if (value > 40) {
                throw new IllegalArgumentException("at most 40 A");
              }
              limit[0] = value;
            },
            new TunableConfig());
    tee.publish("Arm/limit", guarded);
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("Intake/enabled", true);
    values.put("Arm/limit", 60.0);

    var page = tee.requestChanges(values);
    tee.update();
    dashboard.setDouble("Arm/limit", 80);
    tee.update();

    assertThat(page.getNow(null))
        .isEqualTo(
            RequestOutcome.refused(
                List.of(
                    RequestOutcome.Reason.of(
                        RequestOutcome.Layer.TUNABLE,
                        "/Arm/limit",
                        "",
                        "at most 40 A",
                        "/Arm/limit refused the value: at most 40 A"))));
    assertThat(intake.get()).as("all or none").isFalse();
    assertThat(limit[0]).isEqualTo(10);
  }

  @Test
  void anOnTuneThatThrowsNeverStopsTheLoop() {
    var gain =
        TunableDouble.createConfig(
            1.0,
            new TunableConfig()
                .withOnTune(() -> {
                  throw new IllegalStateException("the mechanism rejected it");
                }));
    tee.publish("Arm/kP", gain);

    dashboard.setDouble("Arm/kP", 2.0);
    tee.update();

    assertThat(gain.get()).isEqualTo(2.0);
  }

  @Test
  void onlyTunablesThePageCanChangeAreChanged() {
    var readOnly = TunableDouble.createConfig(2.0, new TunableConfig().withMutable(false));
    tee.publish("Arm/ratio", readOnly);
    tee.update();

    var unknown = tee.requestChanges(Map.of("Nope", 1));
    var fixed = tee.requestChanges(Map.of("Arm/ratio", 3.0));
    tee.update();

    assertThat(unknown.getNow(null).detail())
        .isEqualTo("There is no tunable the page can change at /Nope");
    assertThat(fixed.getNow(null).result()).isEqualTo(RequestOutcome.Result.REFUSED);
    assertThat(tee.isEditable("Arm/ratio")).isFalse();
    assertThat(readOnly.get()).isEqualTo(2.0);
  }

  @Test
  void otherKindsPassStraightThroughReadOnly() {
    TunableBase other = new TunableBase(null) {};
    tee.publish("Vision/layout", other);
    tee.update();

    assertThat(dashboard.getTunable("Vision/layout")).isSameAs(other);
    assertThat(tee.isEditable("Vision/layout")).isFalse();
  }

  /** A unit-aware tunable, built like WPILib's own for measures: a wrapper around a number. */
  static final class Wrapped extends TunableBase implements Tunable.CustomTunable {
    final TunableDouble magnitude;

    Wrapped(double initial) {
      super(null);
      magnitude = TunableDouble.create(initial);
    }

    @Override
    public TunableBase getInnerTunable() {
      return magnitude;
    }
  }

  @Test
  void unitAwareTunablesGoThroughAsTheirNumberInBaseUnits() {
    // WPILib's registry unwraps them before any backend sees them.
    TunableRegistry.registerBackend("", tee);
    var limit = new Wrapped(30.0);
    TunableRegistry.publish("Drive/limit", limit);

    dashboard.setDouble("Drive/limit", 25.0);
    TunableRegistry.update();

    assertThat(limit.magnitude.get()).isEqualTo(25.0);
    assertThat(logged).last().isEqualTo("/Drive/limit=25.0");
    assertThat(tee.isEditable("Drive/limit")).isTrue();
  }

  @Test
  void movingToAnotherBackendHandsOverTheRealTunables() {
    List<TunableBackend.PublishedTunable> moved = tee.removePrefix("");

    assertThat(moved)
        .extracting(TunableBackend.PublishedTunable::tunable)
        .containsExactly(kP, intake);
  }

  @Test
  void aRemovedTunableLeavesTheList() {
    tee.remove("Shooter/kP");
    tee.update();

    assertThat(tee.view()).extracting(TunableTee.Listed::path).containsExactly("/Intake/enabled");
  }

  /** A tunable's limits ride on it as WPILib properties, which dashboards see through the tee. */
  @Test
  void dashboardsSeeATunablesLimits() {
    tee.publish("Vision/hold", TunableDouble.createConfig(0.0, Limits.between(0, 30).config()));

    assertThat(Limits.of(dashboard.getTunable("Vision/hold").getConfig()))
        .isEqualTo(Limits.between(0, 30));
  }

  @Test
  void aDashboardWriteOutsideItsLimitsIsRefusedAndSaysWhy() {
    TunableDouble hold = TunableDouble.createConfig(2.0, Limits.between(0, 30).config());
    tee.publish("Vision/hold", hold);

    dashboard.setDouble("Vision/hold", 45);
    tee.update();
    dashboard.setDouble("Vision/hold", 5);
    tee.update();

    assertThat(hold.get()).as("the second, within the limits").isEqualTo(5.0);
    assertThat(tee.refusals()).containsExactly("/Vision/hold = 45.0: 45 is above its limit of 30");
    assertThat(logged).as("only what was applied").containsExactly("=", "/Vision/hold=5.0");
  }

  @Test
  void aDashboardWriteWhileLockedSaysWhyItWasRefused() {
    whyLocked = "tuning is off";

    dashboard.setDouble("Shooter/kP", 0.9);
    tee.update();

    assertThat(tee.refusals())
        .containsExactly("/Shooter/kP = 0.9: tuning is locked: tuning is off");
  }

  /** The page's changes are checked against every limit before any of them applies. */
  @Test
  void thePageIsRefusedOutsideTheLimitsAndNothingChanges() {
    TunableInt count = TunableInt.createConfig(3, Limits.atLeast(1).config());
    tee.publish("Arm/count", count);
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("Shooter/kP", 0.7);
    values.put("Arm/count", 0);

    var outcome = tee.requestChanges(values);
    tee.update();

    assertThat(outcome.getNow(null))
        .isEqualTo(
            RequestOutcome.refused(
                List.of(
                    RequestOutcome.Reason.of(
                        RequestOutcome.Layer.LIMIT,
                        "/Arm/count",
                        "",
                        "0 is below its limit of 1",
                        "/Arm/count: 0 is below its limit of 1"))));
    assertThat(outcome.getNow(null).detail()).isEqualTo("/Arm/count: 0 is below its limit of 1");
    assertThat(kP.get()).as("all or none").isEqualTo(0.5);
    assertThat(count.get()).isEqualTo(3);
  }

  @Test
  void theListCarriesEachTunablesLimits() {
    tee.publish("Vision/hold", TunableDouble.createConfig(0.0, Limits.atMost(30).config()));
    tee.update();

    assertThat(tee.view())
        .filteredOn(listed -> listed.path().equals("/Vision/hold"))
        .singleElement()
        .extracting(TunableTee.Listed::limits)
        .isEqualTo(Limits.atMost(30));
  }

  /** The test machine's travel and gain, live, published through the tee as WPILib does. */
  private LiveConfig<Fixtures.Machine> publishedMachine() {
    var live =
        LiveConfig.of("machine", Fixtures.machine())
            .number("drive.gains.kP", Limits.between(0, 100))
            .number("drive.limits.maxSpeed", Limits.atMost(10))
            .number("travel.min", Limits.NONE)
            .number("travel.max", Limits.NONE);
    TunableRegistry.registerBackend("", tee);
    TunableRegistry.publish("Machine", live);
    tee.update();
    return live;
  }

  @Test
  void aLiveConfigurationIsKnownWithWhereItsValuesArePublished() {
    var live = publishedMachine();

    assertThat(tee.configs()).containsExactly(live);
    assertThat(tee.pathOf(live, "travel.min")).isEqualTo("/Machine/travel/min");
    assertThat(tee.view())
        .as("shown with their configuration instead")
        .extracting(TunableTee.Listed::path)
        .doesNotContain("/Machine/travel/min");
  }

  /** A dashboard's change to a configuration value is checked by the whole configuration. */
  @Test
  void aDashboardChangeTheConfigurationRefusesSaysWhy() {
    var live = publishedMachine();

    dashboard.setDouble("Machine/drive/limits/maxSpeed", 5);
    tee.update();

    assertThat(live.get()).isEqualTo(Fixtures.machine());
    assertThat(tee.refusals())
        .containsExactly(
            "/Machine/drive/limits/maxSpeed = 5.0: machine: maxSpeed is faster than the motor's"
                + " free speed");
  }

  /** From the page, a configuration's values change together: one rebuild, all or none. */
  @Test
  void thePageChangesAConfigurationsValuesTogether() {
    var live = publishedMachine();
    Map<String, Object> both = new LinkedHashMap<>();
    both.put("Machine/travel/min", 12);
    both.put("Machine/travel/max", 20);

    var outcome = tee.requestChanges(both);
    tee.update();

    assertThat(outcome.getNow(null)).isEqualTo(RequestOutcome.applied());
    assertThat(live.get().travel()).isEqualTo(new Fixtures.Range(12, 20));
    assertThat(logged).last().isEqualTo("/Machine/travel/min,/Machine/travel/max=12.0,20.0");
  }

  @Test
  void thePageIsToldWhyTheConfigurationRefusedAChange() {
    var live = publishedMachine();

    var outcome = tee.requestChanges(Map.of("Machine/travel/min", 12));
    tee.update();

    // The range's check names no value, so it concerns the change's values in that record, and
    // the record, where it is in the configuration.
    assertThat(outcome.getNow(null))
        .isEqualTo(
            RequestOutcome.refused(
                List.of(
                    new RequestOutcome.Reason(
                        RequestOutcome.Layer.RECORD,
                        "Fixtures.Range",
                        List.of("/Machine/travel/min"),
                        "machine",
                        "travel",
                        "min must be below max, got 12.0 and 10.0",
                        "machine: min must be below max, got 12.0 and 10.0"))));
    assertThat(live.get()).isEqualTo(Fixtures.machine());
  }

  @Test
  void thePageIsGivenEveryReasonARequestIsRefused() {
    var live = publishedMachine();
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("Machine/drive/gains/kP", -1);
    values.put("Nowhere", 1);
    values.put("Machine/travel/min", 12);

    var outcome = tee.requestChanges(values);
    tee.update();

    assertThat(outcome.getNow(null).detail().lines())
        .containsExactly(
            "/Machine/drive/gains/kP: -1 is below its limit of 0",
            "There is no tunable the page can change at /Nowhere");
    assertThat(outcome.getNow(null).reasons())
        .extracting(
            RequestOutcome.Reason::layer,
            RequestOutcome.Reason::paths,
            RequestOutcome.Reason::config)
        .containsExactly(
            tuple(RequestOutcome.Layer.LIMIT, List.of("/Machine/drive/gains/kP"), "machine"),
            tuple(RequestOutcome.Layer.VALUE, List.of("/Nowhere"), ""));
    assertThat(live.get()).isEqualTo(Fixtures.machine());
  }

  @Test
  void replayMakesAConfigurationsLoggedChangesTogether() {
    var replayDashboard = new MockTunableBackend();
    var replay =
        new TunableTee(
            replayDashboard,
            () -> null,
            inputs -> {
              inputs.paths = new String[] {"/Machine/travel/min", "/Machine/travel/max"};
              inputs.values = new String[] {"12.0", "20.0"};
            },
            true);
    var live =
        LiveConfig.of("machine", Fixtures.machine())
            .number("travel.min", Limits.NONE)
            .number("travel.max", Limits.NONE);
    TunableRegistry.registerBackend("", replay);
    TunableRegistry.publish("Machine", live);

    replay.update();

    assertThat(live.get().travel()).isEqualTo(new Fixtures.Range(12, 20));
  }

  /** Configuration values go back to committed on the field; they aren't switches. */
  @Test
  void aConfigurationsValuesAreNotSwitches() {
    publishedMachine();

    dashboard.setDouble("Machine/drive/gains/kP", 18);
    tee.update();

    assertThat(tee.changedSinceStartup()).isEmpty();
  }

  // ---- Rollback, conversions and replay, as tables ----

  /** Every warning the tee reports, while a test runs. */
  private final List<String> warnings = new ArrayList<>();

  @BeforeEach
  void hearWarnings() {
    TunableRegistry.setReportWarning(warnings::add);
  }

  @AfterEach
  void stopHearingWarnings() {
    TunableRegistry.setReportWarning(null);
  }

  /**
   * A team's number whose setter refuses anything over 40, and, once it has taken a value, may
   * refuse to be put back.
   */
  private static TunableDouble guarded(double[] held, boolean refusesToGoBack) {
    int[] sets = {0};
    return TunableDouble.createConfig(
        () -> held[0],
        value -> {
          sets[0]++;
          if (value > 40) {
            throw new IllegalArgumentException("at most 40 A");
          }
          if (refusesToGoBack && sets[0] > 1) {
            throw new IllegalStateException("the motor controller didn't answer");
          }
          held[0] = value;
        },
        new TunableConfig());
  }

  /**
   * The page's changes are all or none: when one setter throws partway, those applied before it are
   * put back, the refusal names the value that threw, and a value that can't be put back is
   * reported, never thrown.
   */
  @Test
  void aChangeThatFailsPartwayIsRolledBack() {
    record Group(
        String name,
        boolean firstRefusesToGoBack,
        double first,
        double second,
        double firstAfter,
        List<String> warned) {}
    List<Group> groups =
        List.of(
            new Group("the first goes back", false, 20, 60, 10, List.of()),
            new Group(
                "the first can't go back",
                true,
                20,
                60,
                20,
                List.of(
                    "Pitwall: couldn't put /Arm/first back: java.lang.IllegalStateException: the"
                        + " motor controller didn't answer")));
    for (Group group : groups) {
      TunableRegistry.reset();
      TunableRegistry.setReportWarning(warnings::add);
      warnings.clear();
      double[] first = {10};
      double[] second = {10};
      var each = new TunableTee(new MockTunableBackend(), () -> null, inputs -> {}, false);
      each.publish("Arm/first", guarded(first, group.firstRefusesToGoBack()));
      each.publish("Arm/second", guarded(second, false));
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("Arm/first", group.first());
      values.put("Arm/second", group.second());

      var outcome = each.requestChanges(values);
      each.update();

      assertThat(outcome.getNow(null))
          .as(group.name())
          .isEqualTo(
              RequestOutcome.refused(
                  List.of(
                      RequestOutcome.Reason.of(
                          RequestOutcome.Layer.TUNABLE,
                          "/Arm/second",
                          "",
                          "at most 40 A",
                          "/Arm/second refused the value: at most 40 A"))));
      assertThat(first[0]).as(group.name()).isEqualTo(group.firstAfter());
      assertThat(second[0]).as(group.name()).isEqualTo(10);
      assertThat(warnings).as(group.name()).isEqualTo(group.warned());
    }
  }

  /**
   * A page's number fits a float, an int or a long only when that type can hold it exactly: a whole
   * number for the whole types, within each type's range. One that doesn't fit changes nothing, and
   * says what the tunable takes.
   */
  @Test
  void aNumberIsConvertedOnlyWhenItsTypeCanHoldIt() {
    record Conversion(String type, TunableBase tunable, Number given, @Nullable Object becomes) {}
    List<Conversion> conversions =
        List.of(
            new Conversion("float", TunableFloat.create(1), 0.25, 0.25f),
            new Conversion("float", TunableFloat.create(1), -3, -3f),
            new Conversion("float", TunableFloat.create(1), 1e39, null),
            new Conversion("float", TunableFloat.create(1), -1e39, null),
            new Conversion("int", TunableInt.create(1), 3.0, 3),
            new Conversion("int", TunableInt.create(1), -2147483648.0, Integer.MIN_VALUE),
            new Conversion("int", TunableInt.create(1), 2.5, null),
            new Conversion("int", TunableInt.create(1), 2147483648.0, null),
            new Conversion("int", TunableInt.create(1), -2147483649.0, null),
            new Conversion("long", TunableLong.create(1), 3, 3L),
            new Conversion("long", TunableLong.create(1), 0x1p53, 1L << 53),
            new Conversion("long", TunableLong.create(1), -0x1p63, Long.MIN_VALUE),
            new Conversion("long", TunableLong.create(1), 2.5, null),
            new Conversion("long", TunableLong.create(1), 0x1p63, null));
    for (Conversion conversion : conversions) {
      String row = conversion.type() + " given " + conversion.given();
      tee.publish("Arm/value", conversion.tunable());
      Object before = valueOf(conversion.tunable());

      var outcome = tee.requestChanges(Map.of("Arm/value", conversion.given()));
      tee.update();

      if (conversion.becomes() == null) {
        assertThat(outcome.getNow(null).detail())
            .as(row)
            .isEqualTo("/Arm/value takes a " + conversion.type() + " value");
        assertThat(valueOf(conversion.tunable())).as(row + ": unchanged").isEqualTo(before);
      } else {
        assertThat(outcome.getNow(null)).as(row).isEqualTo(RequestOutcome.applied());
        assertThat(valueOf(conversion.tunable())).as(row).isEqualTo(conversion.becomes());
      }
      tee.remove("Arm/value");
    }
  }

  private static Object valueOf(TunableBase tunable) {
    return switch (tunable) {
      case TunableFloat t -> t.get();
      case TunableInt t -> t.get();
      case TunableLong t -> t.get();
      default -> throw new IllegalArgumentException(tunable.toString());
    };
  }

  /**
   * In a replay, a logged change that can't be made (a number that isn't one, a tunable the program
   * no longer has, one its tunable or its configuration refuses) is reported, and the replay goes
   * on: none throws out of the robot loop.
   */
  @Test
  void aLoggedChangeThatCantBeMadeWarnsAndNeverThrows() {
    record Replayed(String name, String path, String value, String warned) {}
    List<Replayed> replays =
        List.of(
            new Replayed(
                "a number that isn't one",
                "/Shooter/kP",
                "fast",
                "Pitwall: a logged change to /Shooter/kP is unreadable"),
            new Replayed(
                "a fraction for a whole number",
                "/Arm/count",
                "2.5",
                "Pitwall: a logged change to /Arm/count is unreadable"),
            new Replayed(
                "an unknown tunable",
                "/Nowhere",
                "1.0",
                "Pitwall: a logged change is for an unknown tunable, /Nowhere"),
            new Replayed(
                "a tunable that refuses it",
                "/Arm/limit",
                "60.0",
                "Pitwall: /Arm/limit refused a logged change: java.lang.IllegalArgumentException:"
                    + " at most 40 A"),
            new Replayed(
                "a configuration's value that isn't a number",
                "/Machine/travel/min",
                "far",
                "Pitwall: a logged change to /Machine/travel/min is unreadable"),
            new Replayed(
                "a configuration that refuses it",
                "/Machine/travel/min",
                "12.0",
                "Pitwall: machine refused a logged change: machine: min must be below max, got"
                    + " 12.0 and 10.0"));
    for (Replayed replayed : replays) {
      TunableRegistry.reset();
      TunableRegistry.setReportWarning(warnings::add);
      warnings.clear();
      var replay =
          new TunableTee(
              new MockTunableBackend(),
              () -> null,
              inputs -> {
                inputs.paths = new String[] {replayed.path()};
                inputs.values = new String[] {replayed.value()};
              },
              true);
      TunableRegistry.registerBackend("", replay);
      var gain = TunableDouble.create(0.5);
      replay.publish("Shooter/kP", gain);
      replay.publish("Arm/count", TunableInt.create(3));
      double[] limit = {10};
      replay.publish("Arm/limit", guarded(limit, false));
      var live =
          LiveConfig.of("machine", Fixtures.machine())
              .number("travel.min", Limits.NONE)
              .number("travel.max", Limits.NONE);
      TunableRegistry.publish("Machine", live);

      replay.update();

      assertThat(warnings).as(replayed.name()).containsExactly(replayed.warned());
      assertThat(gain.get()).as(replayed.name()).isEqualTo(0.5);
      assertThat(limit[0]).as(replayed.name()).isEqualTo(10);
      assertThat(live.get()).as(replayed.name()).isEqualTo(Fixtures.machine());
    }
  }
}
