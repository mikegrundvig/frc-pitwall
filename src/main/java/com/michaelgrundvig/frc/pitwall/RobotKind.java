package com.michaelgrundvig.frc.pitwall;

/**
 * What the robot program drives: a simulated robot, or real motors. The page treats the two
 * differently, and this is the one place that says how.
 *
 * <p>A program in WPILib's simulation usually drives a simulated robot. It can drive real motors
 * too: on a laptop with a CANivore plugged in, CTRE's hardware-attached simulation sends the
 * program's requests to real devices. WPILib can't tell the two apart (both are its simulation), so
 * the program says which ({@link Pitwall#start(String, int, RobotKind)}).
 */
public enum RobotKind {
  /** A simulated robot in WPILib's simulation: the page may drive it, and tuning starts on. */
  SIMULATED,

  /**
   * Real motors: on a robot, or driven from a laptop through CTRE's hardware-attached simulation.
   * The page never enables, disables or drives them: the Driver Station (or WPILib's simulator GUI,
   * on a laptop) does. Tuning starts off, and is switched on from the page, as on any robot.
   */
  REAL;

  /** Whether tuning starts switched on: for a simulated robot only. Real motors start untuned. */
  public boolean tuningStartsOn() {
    return this == SIMULATED;
  }
}
