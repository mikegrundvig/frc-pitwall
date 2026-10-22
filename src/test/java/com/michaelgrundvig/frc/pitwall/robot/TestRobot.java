package com.michaelgrundvig.frc.pitwall.robot;

import static com.michaelgrundvig.frc.world.robot.MechanismWiring.CANCODER;
import static com.michaelgrundvig.frc.world.robot.MechanismWiring.Controller.TALON_FX;
import static org.wpilib.units.Units.Degrees;
import static org.wpilib.units.Units.DegreesPerSecond;
import static org.wpilib.units.Units.DegreesPerSecondPerSecond;
import static org.wpilib.units.Units.Hertz;
import static org.wpilib.units.Units.Inches;
import static org.wpilib.units.Units.KilogramSquareMeters;
import static org.wpilib.units.Units.Kilograms;
import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.Milliseconds;
import static org.wpilib.units.Units.Rotations;

import com.michaelgrundvig.frc.world.physical.Inertia;
import com.michaelgrundvig.frc.world.robot.Body;
import com.michaelgrundvig.frc.world.robot.CameraConfig;
import com.michaelgrundvig.frc.world.robot.Driven.AbsoluteEncoder;
import com.michaelgrundvig.frc.world.robot.DrivetrainGeometry;
import com.michaelgrundvig.frc.world.robot.Gearing;
import com.michaelgrundvig.frc.world.robot.Imus;
import com.michaelgrundvig.frc.world.robot.Joint;
import com.michaelgrundvig.frc.world.robot.Link;
import com.michaelgrundvig.frc.world.robot.MechanismControl;
import com.michaelgrundvig.frc.world.robot.Motor;
import com.michaelgrundvig.frc.world.robot.Output;
import com.michaelgrundvig.frc.world.robot.Part;
import com.michaelgrundvig.frc.world.robot.Position;
import com.michaelgrundvig.frc.world.robot.Range;
import com.michaelgrundvig.frc.world.robot.RobotDefinition;
import com.michaelgrundvig.frc.world.robot.Sensor;
import java.util.List;
import org.wpilib.math.geometry.Translation3d;

/**
 * A robot for the Robot view's tests, with one joint of each kind the view draws and one interlock
 * of each kind: an intake's pivot with its roller on it, which runs only once the pivot is out; an
 * elevator carrying an arm, which keeps out of the bumper's band while the elevator is low; a
 * flywheel that runs one way; a claw that runs only while its beam is clear; a beam break and a
 * rangefinder.
 */
public final class TestRobot {
  private TestRobot() {}

  static final Position PIVOT_IN = Position.degrees("in", 1.5);
  static final Position PIVOT_OUT = Position.degrees("out", 130);

  /** An intake's pivot, which stands upright when it is in and swings forward and down. */
  static final Joint.Revolute PIVOT =
      Joint.pivot("pivot", Part.CHASSIS, new Translation3d(0.25, 0, 0.32), Joint.PITCH_DOWN)
          .range(Range.degrees(0, 135))
          .rest(PIVOT_IN)
          .carries(
              Link.box(
                  "intake arm", new Translation3d(0, 0, 0.2), new Translation3d(0.05, 0.55, 0.36)))
          .positions(PIVOT_IN, PIVOT_OUT)
          .motors(Motor.KRAKEN_X60.through(Gearing.of(25)).on(TALON_FX.can(32)))
          .absoluteEncoder(CANCODER.can(34).offset(Rotations.of(0.82)))
          .control(
              MechanismControl.position(30)
                  .withProfile(DegreesPerSecond.of(450), DegreesPerSecondPerSecond.of(1700)));

  static final Output INTAKING = Output.rpm("intaking", 2000);
  static final Output OUTTAKING = Output.volts("outtaking", -4);

  /** Its roller, which runs both ways, and only once the pivot is out of the frame. */
  static final Joint.Revolute ROLLER =
      Joint.roller("roller", PIVOT, new Translation3d(0, 0, 0.35))
          .outputs(INTAKING, OUTTAKING)
          .motors(Motor.KRAKEN_X60.through(Gearing.of(3)).on(TALON_FX.can(31)))
          .runsOnlyWhen(PIVOT.atLeast(Degrees.of(110)))
          .control(MechanismControl.velocity(0.02));

  static final Position ELEVATOR_DOWN = Position.of("down", Inches.of(0));
  static final Position ELEVATOR_CLEAR = Position.of("clear", Inches.of(32));

  static final Joint.Prismatic ELEVATOR =
      Joint.elevator("elevator", Part.CHASSIS, new Translation3d(0, 0, 0.1))
          .range(Range.meters(0, 1.2))
          .rest(ELEVATOR_DOWN)
          .positions(ELEVATOR_DOWN, ELEVATOR_CLEAR)
          .motors(
              Motor.KRAKEN_X60
                  .through(Gearing.of(5))
                  .drum(Inches.of(0.75))
                  .on(TALON_FX.can(20), TALON_FX.can(21).clockwise()))
          .absoluteEncoder(
              AbsoluteEncoder.of().ratio(1.0 / 12).on(CANCODER.can(24).offset(Rotations.of(0.31))))
          .control(MechanismControl.position(30).withKg(0.35));

  static final Position ARM_STOWED = Position.degrees("stowed", -45);
  static final Position ARM_SCORING = Position.degrees("scoring", 120);

  /** An arm on the elevator, kept out of the bumper's band while the elevator is too low. */
  static final Joint.Revolute ARM =
      Joint.pivot("arm", ELEVATOR, new Translation3d(0.1, 0, 0.3), Joint.PITCH_UP)
          .range(Range.degrees(-60, 150))
          .rest(ARM_STOWED)
          .positions(ARM_STOWED, ARM_SCORING)
          .motors(Motor.KRAKEN_X60.through(Gearing.of(60)).on(TALON_FX.can(22)))
          .absoluteEncoder(CANCODER.can(23).offset(Rotations.of(0.12)))
          .keepsOut(Range.degrees(-10, 30))
          .when(ELEVATOR.atMost(Inches.of(32)))
          .control(MechanismControl.position(30).withKg(0.35, Degrees.of(-90)));

  static final Output SHOOTING = Output.rpm("shooting", 3000);

  /** A flywheel, which runs one way and coasts when nothing is asked of it. */
  static final Joint.Revolute SHOOTER =
      Joint.roller("shooter", Part.CHASSIS, new Translation3d(-0.2, 0, 0.45))
          .outputs(SHOOTING)
          .motors(Motor.KRAKEN_X60.through(Gearing.of(1)).on(TALON_FX.can(52)))
          .control(MechanismControl.velocity(0.02).coasting());

  static final Sensor.Switch CLAW_BEAM = Sensor.Switch.beam("claw beam", PIVOT);

  static final Sensor.Rangefinder HOPPER =
      Sensor.Rangefinder.of("hopper", Part.CHASSIS, Meters.of(1.3), Degrees.of(27));

  static final Output GRIPPING = Output.rpm("gripping", 600);

  /** A claw's wheels, which run only while the claw's beam sees nothing. */
  static final Joint.Revolute CLAW =
      Joint.roller("claw", ARM, new Translation3d(0.3, 0, 0))
          .outputs(GRIPPING)
          .motors(Motor.KRAKEN_X44.through(Gearing.of(4)).on(TALON_FX.can(60)))
          .runsOnlyWhen(CLAW_BEAM.clear())
          .control(MechanismControl.velocity(0.02));

  private static final CameraConfig.Sensor CAMERA =
      CameraConfig.Sensor.builder()
          .widthPixels(1280)
          .heightPixels(800)
          .fieldOfView(Degrees.of(80))
          .fieldOfViewAcross(CameraConfig.Across.DIAGONAL)
          .frameRate(Hertz.of(30))
          .latency(Milliseconds.of(30))
          .sightRange(Meters.of(7))
          .build();

  /** A camera on the chassis, facing forward and to the left, tilted up. */
  static final CameraConfig FRONT_CAMERA =
      CameraConfig.builder()
          .name("front")
          .forward(Inches.of(10))
          .left(Inches.of(8))
          .up(Inches.of(20))
          .roll(Degrees.of(0))
          .tiltUp(Degrees.of(10))
          .yaw(Degrees.of(20))
          .sensor(CAMERA)
          .noiseScale(1.0)
          .build();

  /** A camera that rides on the arm, so it is where the elevator and the arm put it. */
  static final CameraConfig ARM_CAMERA =
      CameraConfig.builder()
          .name("on-arm")
          .forward(Inches.of(4))
          .left(Inches.of(0))
          .up(Inches.of(2))
          .roll(Degrees.of(0))
          .tiltUp(Degrees.of(0))
          .yaw(Degrees.of(0))
          .sensor(CAMERA)
          .noiseScale(1.0)
          .joint(ARM)
          .build();

  /** The robot. */
  public static RobotDefinition definition() {
    return RobotDefinition.builder()
        .name("test")
        .body(body())
        .drivetrain(DrivetrainGeometry.inset(modules(), body(), Inches.of(2.625)))
        .imus(Imus.UNCHECKED)
        .joints(List.of(PIVOT, ROLLER, ELEVATOR, ARM, SHOOTER, CLAW))
        .sensors(List.of(CLAW_BEAM, HOPPER))
        .cameras(List.of(FRONT_CAMERA, ARM_CAMERA))
        .build();
  }

  private static Body body() {
    return Body.builder()
        .frameLength(Inches.of(28))
        .frameWidth(Inches.of(24))
        .overallHeight(Inches.of(30))
        .bumperThickness(Inches.of(3.5))
        .bumperBottom(Inches.of(2.5))
        .bumperTop(Inches.of(5.75))
        .weight(Kilograms.of(60))
        .inertia(
            Body.Turning.tensor(
                Inertia.builder().ixx(6).iyy(6).izz(6).ixy(0).ixz(0).iyz(0).build()))
        .cg(new Translation3d(0, 0, 0.2))
        .build();
  }

  private static DrivetrainGeometry.ModuleType modules() {
    return DrivetrainGeometry.ModuleType.builder()
        .driveMotor(Motor.KRAKEN_X60)
        .steerMotor(Motor.KRAKEN_X44)
        .wheelRadius(Inches.of(2))
        .driveGearing(Gearing.builder().ratio(6.75).efficiency(1.0).build())
        .steerGearing(Gearing.builder().ratio(12.8).efficiency(1.0).build())
        .couplingRatio(0)
        .wheelCoefficientOfFriction(1.2)
        .driveInertia(KilogramSquareMeters.of(0.001))
        .steerInertia(KilogramSquareMeters.of(0.004))
        .build();
  }
}
