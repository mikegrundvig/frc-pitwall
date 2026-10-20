package com.michaelgrundvig.frc.pitwall;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What the page does for each kind of robot. Whether it has the simulator's controls is the
 * runtime's to test, with WPILib's HAL ({@code PitwallRobotTest}): here, what the kind says alone.
 */
class RobotKindTest {
  /**
   * Tuning starts on for a simulated robot only: real motors, on a robot or a laptop, start off.
   */
  @Test
  void onlyASimulatedRobotStartsWithTuningOn() {
    assertThat(RobotKind.SIMULATED.tuningStartsOn()).isTrue();
    assertThat(RobotKind.REAL.tuningStartsOn()).isFalse();
  }
}
