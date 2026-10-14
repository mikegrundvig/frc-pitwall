package com.michaelgrundvig.frc.pitwall.world;

import com.michaelgrundvig.frc.pitwall.scene.Inspection;
import java.util.Optional;
import org.wpilib.math.geometry.Pose2d;

/**
 * Which of the simulator's parts each part of the robot is, by the name the robot's simulation
 * declared it under ({@code pitwall.simulator().components()}), so that a click on the part in the
 * World view ends in its faults. The World view knows the robot's parts and the simulation knows
 * what it called them. This is where the two meet, and only a simulated robot has an answer.
 */
public interface SimulatedParts {
  /** A robot that isn't simulated: none of its parts can be made to fail. */
  SimulatedParts NONE =
      new SimulatedParts() {
        @Override
        public Optional<String> camera(String name) {
          return Optional.empty();
        }

        @Override
        public Optional<String> gyro() {
          return Optional.empty();
        }

        @Override
        public Optional<String> backupImu() {
          return Optional.empty();
        }

        @Override
        public Optional<String> module(int index) {
          return Optional.empty();
        }

        @Override
        public Optional<String> robot() {
          return Optional.empty();
        }

        @Override
        public Optional<Inspection.FaultOffer> placingTheRobotAt(Pose2d pose) {
          return Optional.empty();
        }
      };

  /** The camera with this name in the robot's definition. */
  Optional<String> camera(String name);

  /** The gyro's IMU. */
  Optional<String> gyro();

  /** The backup IMU, which the World view doesn't draw: its faults are offered on the robot. */
  Optional<String> backupImu();

  /**
   * A swerve module.
   *
   * @param index its place in the drivetrain's order: front left, front right, back left, back
   *     right
   */
  Optional<String> module(int index);

  /** The robot as a whole: what can be shoved, hit, tilted and set down somewhere. */
  Optional<String> robot();

  /**
   * The fault that sets the robot down at a pose, with the pose's numbers in it: the simulation's
   * own "placed", offered where a place on the field is clicked. Empty where the robot can't be set
   * down.
   *
   * @param pose where, in the field's frame
   */
  Optional<Inspection.FaultOffer> placingTheRobotAt(Pose2d pose);
}
