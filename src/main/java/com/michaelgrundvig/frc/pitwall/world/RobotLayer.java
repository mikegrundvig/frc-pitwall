package com.michaelgrundvig.frc.pitwall.world;

import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.Radians;

import com.michaelgrundvig.frc.pitwall.robot.Moment;
import com.michaelgrundvig.frc.pitwall.scene.Legend;
import com.michaelgrundvig.frc.pitwall.scene.SceneBuilder;
import com.michaelgrundvig.frc.pitwall.scene.SceneState;
import com.michaelgrundvig.frc.pitwall.scene.Style;
import com.michaelgrundvig.frc.world.robot.Body;
import com.michaelgrundvig.frc.world.robot.CameraConfig;
import com.michaelgrundvig.frc.world.robot.ImuMount;
import com.michaelgrundvig.frc.world.robot.Joint;
import com.michaelgrundvig.frc.world.robot.RobotDefinition;
import com.michaelgrundvig.frc.world.robot.RobotMath;
import com.michaelgrundvig.frc.world.robot.Sensor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.wpilib.math.geometry.Pose3d;
import org.wpilib.math.geometry.Transform3d;
import org.wpilib.math.geometry.Translation2d;
import org.wpilib.math.geometry.Translation3d;

/**
 * The robot where localization believed it was in a loop, as its configuration describes it. It
 * draws:
 *
 * <ul>
 *   <li>the frame and the bumpers, with an arrow out the front;
 *   <li>each wheel at its module, turned to its measured angle;
 *   <li>each camera at its exact mount, as a short arrow along its optical axis;
 *   <li>the gyro's IMU, as a small block where its mount puts it, turned as it's mounted, with an
 *       arrow along its own x axis.
 * </ul>
 *
 * <p>It also places the frames that other layers draw in, each of which the page can switch on and
 * off by itself: the wheels' drive arrows ({@link #wheelArrows}), the cameras' names ({@link
 * #cameraNames}) and views ({@link CameraViews}), and the switches and rangefinders ({@link
 * #sensors}). A camera or a sensor that rides on a joint is where the joint was in that loop.
 *
 * <p>A click on a camera says how it's mounted, offers its calibration, and shows its name and its
 * view until the details close. A click on the IMU says how it's mounted and whether its
 * accelerometer has been checked. A click on a sensor says what it is and where.
 */
final class RobotLayer extends WorldLayer {
  /** The robot's frame on the page: the frame the page's Follow follows. */
  static final String FRAME = "robot";

  /** About how tall a bumper is, in meters. The rules allow 5 in. */
  private static final double BUMPER_HEIGHT = 0.127;

  /** How far above the floor bumpers ride. */
  private static final double BUMPER_CLEARANCE = 0.02;

  /** How far above the frame's top the arrow out the front is drawn. */
  private static final double FRONT_ARROW_RISE = 0.02;

  /** How far past the front bumper the arrow out the front reaches. */
  private static final double FRONT_ARROW_REACH = 0.2;

  /** How far above its wheel's top a wheel's drive arrow is drawn. */
  private static final double DRIVE_ARROW_RISE = 0.03;

  /** How far a wheel's speed must change, in meters of its arrow, before the page hears of it. */
  private static final double DRIVE_ARROW_DEADBAND = 0.01;

  /** The gyro's IMU on the page. This is its frame, turned as it's mounted. */
  private static final String IMU = "imu.gyro";

  /** The IMU's block, which is about a Pigeon 2's size, and the size AdvantageScope draws. */
  private static final double IMU_SIZE = 0.05;

  private static final double IMU_THICKNESS = 0.02;

  /** The arrow along the IMU's own x axis, which shows how it's turned. */
  private static final double IMU_ARROW = 0.1;

  /** A switch's or a rangefinder's block, which is about a CANrange's or a limit switch's size. */
  private static final double SENSOR_SIZE = 0.03;

  /** About how wide a swerve wheel is. */
  private static final double WHEEL_WIDTH = 0.038;

  /** The arrow along a camera's optical axis. */
  private static final double CAMERA_ARROW = 0.3;

  /**
   * How far from its camera a camera's name starts, along the way the camera faces: past the
   * bumpers, and near enough to be the camera's.
   */
  static final double VIEW_STUB_METERS = 1.0;

  /** How far a camera's name stays clear of the bumpers. */
  private static final double LABEL_CLEARANCE = 0.15;

  /** How far apart two cameras' names stay, center to center. */
  private static final double LABEL_SPACING = 0.35;

  /** How far above its camera a camera's name is drawn. */
  private static final double LABEL_RISE = 0.1;

  /** A camera's name is moved out along the way the camera faces in steps this long. */
  private static final double LABEL_STEP = 0.1;

  /** The most steps a name is moved out, which is 10 m and past any robot. */
  private static final int LABEL_STEPS = 100;

  /**
   * How far the estimate must move before the page hears of it. A still robot's estimate wanders a
   * few millimeters as each image corrects it, which is no news, and a centimeter is less than a
   * pixel of a field on a laptop.
   */
  static final double DEADBAND_METERS = 0.01;

  static final double DEADBAND_RADIANS = Math.toRadians(0.5);

  /**
   * How far a wheel must turn before the page hears of it. A wheel at rest reads a few tenths of a
   * degree either way as its encoder settles, and a degree is less than a pixel of its drawing.
   */
  static final double STEER_DEADBAND_RADIANS = Math.toRadians(1);

  /** A wheel's frame only turns, so the deadband for its place is the least there is: 1 mm. */
  private static final double WHEEL_DEADBAND_METERS = 0.001;

  /**
   * How far ahead a wheel's drive arrow reaches. Its tip is where the wheel will be after this long
   * at its speed.
   */
  static final double ARROW_SECONDS = 0.2;

  /**
   * The swerve modules' names on the page, in the order the drivetrain lists them ({@link
   * RobotDefinition#moduleTranslations}).
   */
  static final List<String> MODULES =
      List.of("front-left", "front-right", "back-left", "back-right");

  /** No joint's position given, which the robot's maths takes as every joint at its reference. */
  private static final Map<Joint, Double> JOINTS_AT_REST = Map.of();

  private final RobotMath math;
  private final Body body;

  /** The robot's joints by their labels, which is how a moment names them. */
  private final Map<String, Joint> joints = new HashMap<>();

  /** Whether any camera or sensor rides on a joint, and so moves with it. */
  private final boolean somethingIsCarried;

  /** Each wheel's radius, in meters. */
  private final double[] radii;

  private final List<CameraConfig> cameras;

  /**
   * Each camera's mount on the robot, in the cameras' order, as it is described: a camera on a
   * joint where its joint rests, until a loop says where the joint is.
   */
  private final Transform3d[] mounts;

  /** The switches and rangefinders. */
  private final List<Sensor.Placed> sensors;

  /** Each one's mount on the robot, in their order, as described: with every joint at rest. */
  private final Transform3d[] sensorMounts;

  private final ImuMount gyro;
  private final Function<String, Optional<String>> cameraAction;
  private final Translation2d[] modules;

  /** Each module's frame and speed on the page. They are named once, since every loop uses them. */
  private final String[] moduleFrames;

  private final String[] moduleSpeeds;

  RobotLayer(RobotDefinition robot, Function<String, Optional<String>> cameraAction) {
    body = robot.body();
    for (Joint joint : robot.joints()) {
      joints.put(joint.label(), joint);
    }
    radii = new double[robot.drivetrain().corners().size()];
    for (int i = 0; i < radii.length; i++) {
      radii[i] = robot.drivetrain().module(i).wheelRadius().in(Meters);
    }
    cameras = List.copyOf(robot.cameras());
    math = robot.math();
    mounts = new Transform3d[cameras.size()];
    for (int i = 0; i < cameras.size(); i++) {
      mounts[i] = asMount(math.cameraPose(cameras.get(i), JOINTS_AT_REST));
    }
    sensors =
        math.sensors().stream()
            .filter(Sensor.Placed.class::isInstance)
            .map(Sensor.Placed.class::cast)
            .toList();
    sensorMounts = new Transform3d[sensors.size()];
    for (int i = 0; i < sensors.size(); i++) {
      sensorMounts[i] = asMount(math.sensorPose(sensors.get(i), JOINTS_AT_REST));
    }
    somethingIsCarried =
        cameras.stream().anyMatch(camera -> camera.joint().isPresent())
            || sensors.stream().anyMatch(sensor -> sensor.part() instanceof Joint);
    gyro = robot.imus().gyro();
    this.cameraAction = cameraAction;
    modules = robot.moduleTranslations();
    int count = Math.min(modules.length, MODULES.size());
    moduleFrames = new String[count];
    moduleSpeeds = new String[count];
    for (int i = 0; i < count; i++) {
      moduleFrames[i] = "module." + MODULES.get(i);
      moduleSpeeds[i] = "speed." + MODULES.get(i);
    }
  }

  /** A pose in the robot's frame, as the mount that puts a part there. */
  private static Transform3d asMount(Pose3d inTheRobotsFrame) {
    return new Transform3d(inTheRobotsFrame.getTranslation(), inTheRobotsFrame.getRotation());
  }

  @Override
  public String id() {
    return "robot";
  }

  @Override
  public String title() {
    return "Robot";
  }

  @Override
  public void describe(SceneBuilder scene) {
    scene.frame(FRAME, SceneBuilder.ROOT).deadband(FRAME, DEADBAND_METERS, DEADBAND_RADIANS);
    describeBody(scene);
    describeWheels(scene);
    describeCameras(scene);
    for (int i = 0; i < sensors.size(); i++) {
      scene.frame("sensor." + i, FRAME, sensorMounts[i]);
    }
    describeImu(scene);
  }

  /** The frame as an outline, the bumpers as a solid, and an arrow out the front. */
  private void describeBody(SceneBuilder scene) {
    double height = body.height().in(Meters);
    double bumperLength = body.bumperLength().in(Meters);
    scene
        .box("robot.frame", FRAME)
        .size(body.frameLength().in(Meters), body.frameWidth().in(Meters), height)
        .at(0, 0, height / 2)
        .outline()
        .style(Style.ACCENT);
    scene
        .box("robot.bumpers", FRAME)
        .size(bumperLength, body.bumperWidth().in(Meters), BUMPER_HEIGHT)
        .at(0, 0, BUMPER_CLEARANCE + BUMPER_HEIGHT / 2)
        .style(Style.ACCENT);
    scene
        .arrow("robot.front", FRAME)
        .from(0, 0, height + FRONT_ARROW_RISE)
        .to(bumperLength / 2 + FRONT_ARROW_REACH, 0, height + FRONT_ARROW_RISE)
        .style(Style.ACCENT);
  }

  /**
   * Each wheel in a frame of its own at its module, which {@link #update} turns to the wheel's
   * measured angle. The wheel is a cylinder lying on its side. Its drive arrow is in a layer of its
   * own ({@link #wheelArrows}).
   */
  private void describeWheels(SceneBuilder scene) {
    for (int i = 0; i < moduleFrames.length; i++) {
      String module = MODULES.get(i);
      String frame = moduleFrames[i];
      double radius = radii[i];
      scene
          .frame(frame, FRAME, modules[i].getX(), modules[i].getY(), 0, 0, 0, 0)
          .deadband(frame, WHEEL_DEADBAND_METERS, STEER_DEADBAND_RADIANS)
          .value(moduleSpeeds[i], DRIVE_ARROW_DEADBAND);
      // A cylinder's axis is its z. Rolled a quarter turn, the axis lies along the wheel's axle.
      scene
          .cylinder("wheel." + module, frame)
          .radius(radius)
          .length(WHEEL_WIDTH)
          .at(0, 0, radius, Math.PI / 2, 0, 0)
          .style(Style.ACCENT);
    }
  }

  /**
   * Each camera in a frame of its own at its mount, marked by a short arrow along its optical axis.
   * A click on the arrow says how the camera is mounted, and shows its name and its view until the
   * details close; the names and the views are layers of their own, off until switched on.
   */
  private void describeCameras(SceneBuilder scene) {
    for (int i = 0; i < cameras.size(); i++) {
      CameraConfig camera = cameras.get(i);
      String frame = WorldWords.cameraFrame(camera.id());
      scene.frame(frame, FRAME, mounts[i]);
      scene
          .arrow(frame + ".axis", frame)
          .to(CAMERA_ARROW, 0, 0)
          .style(Style.ACCENT)
          .inspect(() -> WorldWords.camera(camera, cameraAction))
          .reveal(CameraViews.view(camera), name(camera));
    }
  }

  /** The id of the shape that is a camera's name. */
  private static String name(CameraConfig camera) {
    return WorldWords.cameraFrame(camera.id()) + ".name";
  }

  /** Each wheel's drive arrow, along its frame's x and as long as the module's speed value says. */
  StillLayer wheelArrows() {
    return new StillLayer(
        "wheel-arrows",
        "Wheel arrows",
        scene -> {
          for (int i = 0; i < moduleFrames.length; i++) {
            double aboveTheWheel = 2 * radii[i] + DRIVE_ARROW_RISE;
            scene
                .arrow("drive." + MODULES.get(i), moduleFrames[i])
                .from(0, 0, aboveTheWheel)
                .to(1, 0, aboveTheWheel)
                .length(moduleSpeeds[i])
                .style(Style.ACCENT);
          }
        },
        List.of(
            Legend.arrow(
                Style.ACCENT,
                "Where a wheel is driving, as it was measured: as long as it travels in 0.2 s")));
  }

  /** Each camera's name, off the robot and off the other names. */
  StillLayer cameraNames() {
    return new StillLayer(
        "camera-names",
        "Camera names",
        scene -> {
          List<Translation3d> labels = labelPlaces(cameras, body);
          for (int i = 0; i < cameras.size(); i++) {
            Translation3d label = labels.get(i);
            scene
                .label(name(cameras.get(i)), FRAME)
                .text(cameras.get(i).name())
                .at(label.getX(), label.getY(), label.getZ());
          }
        },
        List.of(Legend.text(Style.MUTED, "A camera's name, as the robot's definition has it")));
  }

  /** Each switch and rangefinder as a small block at its mount, and a rangefinder's cone. */
  StillLayer sensors() {
    return new StillLayer(
        "sensors",
        "Sensors",
        scene -> {
          for (int i = 0; i < sensors.size(); i++) {
            Sensor.Placed sensor = sensors.get(i);
            String frame = "sensor." + i;
            scene
                .box(frame + ".block", frame)
                .size(SENSOR_SIZE, SENSOR_SIZE, SENSOR_SIZE)
                .style(Style.ACCENT)
                .inspect(() -> WorldWords.sensor(sensor));
            if (sensor instanceof Sensor.Rangefinder rangefinder) {
              double cone = rangefinder.fieldOfView().in(Radians);
              scene
                  .frustum(frame + ".cone", frame)
                  .fov(cone, cone)
                  .range(0, rangefinder.range().in(Meters))
                  .inspect(() -> WorldWords.sensor(sensor));
            }
          }
        },
        List.of(
            Legend.fill(Style.ACCENT, "A switch or a rangefinder, where it is mounted"),
            Legend.outline(Style.MUTED, "A rangefinder's cone, out to its range")));
  }

  @Override
  public List<Legend> legend() {
    return List.of(
        Legend.fill(
            Style.ACCENT,
            "The robot where localization believed it was: its frame, bumpers and wheels"),
        Legend.arrow(
            Style.ACCENT,
            "Out the front of the robot; along each camera's optical axis; along the gyro's x"));
  }

  /** The gyro's IMU as a block in a frame turned as it's mounted, with an arrow along its x. */
  private void describeImu(SceneBuilder scene) {
    scene.frame(IMU, FRAME, new Transform3d(gyro.position(), gyro.orientation()));
    scene
        .box(IMU + ".block", IMU)
        .size(IMU_SIZE, IMU_SIZE, IMU_THICKNESS)
        .style(Style.ACCENT)
        .inspect(() -> WorldWords.imu("Gyro IMU", gyro));
    scene
        .arrow(IMU + ".x", IMU)
        .to(IMU_ARROW, 0, 0)
        .style(Style.ACCENT)
        .inspect(() -> WorldWords.imu("Gyro IMU", gyro));
  }

  /**
   * Where each camera's name is drawn, in the robot's frame.
   *
   * <p>A name starts where its camera's view stub ends, seen from above. While that spot is over
   * the robot, or too near a name placed before, the name moves on along the way the camera faces,
   * a step at a time. So the names stay off the robot and off each other.
   */
  static List<Translation3d> labelPlaces(List<CameraConfig> cameras, Body body) {
    double halfLength = body.bumperLength().in(Meters) / 2 + LABEL_CLEARANCE;
    double halfWidth = body.bumperWidth().in(Meters) / 2 + LABEL_CLEARANCE;
    List<Translation3d> places = new ArrayList<>();
    for (CameraConfig camera : cameras) {
      double facing = camera.yaw().in(Radians);
      double x = camera.forward().in(Meters);
      double y = camera.left().in(Meters);
      // How far out from the camera the name is, along the way the camera faces.
      double out = VIEW_STUB_METERS;
      for (int step = 0; step < LABEL_STEPS; step++) {
        double atX = x + out * Math.cos(facing);
        double atY = y + out * Math.sin(facing);
        boolean overTheRobot = Math.abs(atX) < halfLength && Math.abs(atY) < halfWidth;
        if (!overTheRobot && !isNearAnother(places, atX, atY)) {
          break;
        }
        out += LABEL_STEP;
      }
      places.add(
          new Translation3d(
              x + out * Math.cos(facing),
              y + out * Math.sin(facing),
              camera.up().in(Meters) + LABEL_RISE));
    }
    return places;
  }

  /** Whether a spot, seen from above, is too near a name already placed. */
  private static boolean isNearAnother(List<Translation3d> places, double x, double y) {
    for (Translation3d place : places) {
      if (Math.hypot(place.getX() - x, place.getY() - y) < LABEL_SPACING) {
        return true;
      }
    }
    return false;
  }

  /**
   * The robot stands where the loop's estimate has it, as high as the ground under it and tilted as
   * its IMU said, with each wheel turned and driving as it was measured, and whatever rides on a
   * joint where that joint was.
   */
  @Override
  void draw(Moment moment, SceneState state) {
    Moment.OfWorld world = moment.world();
    if (world.estimate().isPresent()) {
      state.pose(FRAME, world.estimate().get().pose());
    }
    List<Moment.OfModule> measured = world.modules();
    for (int i = 0; i < measured.size() && i < moduleFrames.length; i++) {
      state.pose(moduleFrames[i], modules[i].getX(), modules[i].getY(), measured.get(i).radians());
      // The arrow reaches to where the wheel will be in a moment: length = speed × time.
      state.value(moduleSpeeds[i], measured.get(i).metersPerSecond() * ARROW_SECONDS);
    }
    if (somethingIsCarried) {
      drawWhatTheJointsCarry(positions(moment), state);
    }
  }

  /** Puts each camera and sensor that rides on a joint where the joint's position has it. */
  private void drawWhatTheJointsCarry(Map<Joint, Double> positions, SceneState state) {
    for (CameraConfig camera : cameras) {
      if (camera.joint().isPresent()) {
        state.pose(WorldWords.cameraFrame(camera.id()), math.cameraPose(camera, positions));
      }
    }
    for (int i = 0; i < sensors.size(); i++) {
      if (sensors.get(i).part() instanceof Joint) {
        state.pose("sensor." + i, math.sensorPose(sensors.get(i), positions));
      }
    }
  }

  /**
   * Where each joint was in a loop, for the joints the record has a position of. The robot's maths
   * takes a joint without one as at its reference.
   */
  private Map<Joint, Double> positions(Moment moment) {
    Map<Joint, Double> positions = new HashMap<>();
    for (Moment.OfJoint each : moment.joints()) {
      Joint joint = joints.get(each.label());
      if (joint != null && Double.isFinite(each.position())) {
        positions.put(joint, each.position());
      }
    }
    return positions;
  }

  /**
   * How wide a camera sees.
   *
   * @param acrossRadians its field of view from side to side
   * @param upAndDownRadians its field of view from top to bottom
   */
  record FieldOfView(double acrossRadians, double upAndDownRadians) {}

  /**
   * A camera's field of view from side to side and from top to bottom, worked out from its field of
   * view across the diagonal and its image's shape, for a camera without distortion.
   *
   * <p>The focal length, in pixels, is the distance from the lens at which half the image's
   * diagonal spans half the diagonal's angle:
   *
   * <pre>focal = (diagonal / 2) / tan(diagonal angle / 2)</pre>
   *
   * <p>At that distance, half the image's width spans half the angle across, and likewise its
   * height:
   *
   * <pre>angle across = 2 × atan((width / 2) / focal)</pre>
   */
  static FieldOfView fieldOfView(CameraConfig.Sensor sensor) {
    double widthPixels = sensor.widthPixels();
    double heightPixels = sensor.heightPixels();
    double diagonalPixels = Math.hypot(widthPixels, heightPixels);
    double focalPixels =
        diagonalPixels / 2 / Math.tan(sensor.diagonalFieldOfView().in(Radians) / 2);
    return new FieldOfView(
        2 * Math.atan(widthPixels / 2 / focalPixels),
        2 * Math.atan(heightPixels / 2 / focalPixels));
  }
}
