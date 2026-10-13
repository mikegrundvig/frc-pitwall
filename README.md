# Pitwall

Pitwall is the robot program's own web page: status, actions, live tuning, experiments, devices,
the robot's joints drawn live, and the simulator's controls, served by the program itself on port
5820 (http://localhost:5820 in the simulator). It's a library of its own
(`com.michaelgrundvig.frc.pitwall`), free of any team's robot code. It reads the robot model
(`com.michaelgrundvig.frc:frc-robot-model`): handed a robot's definition, it draws that robot's
joints as the model says them. Everything else the robot program contributes to it, as data. The
robot template's README describes each part; this file is for contributors, and covers its Robot
view and its scenes: the World view.

**Another port:** `Pitwall.start(name)` serves on 5820 unless the `pitwall.port` system property
names another (`-Dpitwall.port=5920`; 0 picks a free one, and Pitwall prints the one it took). In
the template, `./gradlew run -Ppitwall.port=5920` passes it to the simulator. Run a second simulator
beside yours (an agent's, another branch's) on another port: a page left open on 5820 reconnects to
whatever answers there, and drives it.

## Using it

It isn't published anywhere. A build takes it as source, as a git submodule built as part of that
build (a Gradle composite build), so there is no version to bump:

```
git submodule add https://github.com/mikegrundvig/frc-pitwall.git pitwall
git submodule add https://github.com/mikegrundvig/frc-robot-model.git model
```

```groovy
// settings.gradle
includeBuild("pitwall")
includeBuild("model") // Pitwall reads the robot model, and uses the build's own

// build.gradle
dependencies {
    implementation "com.michaelgrundvig.frc:frc-pitwall"
}
```

Pitwall carries the model as a submodule of its own too (`model/`), which is built only when
Pitwall is the build that runs: its own `./gradlew ci`. A build that includes Pitwall provides the
model itself, since Gradle refuses two builds that provide one module.

WPILib, Commands v3 and AdvantageKit are the robot program's own: Pitwall compiles against them and
doesn't bring them, so the program that uses it must have them (a WPILib project does). AdvantageKit
is optional at run time. A change to Pitwall is made in that folder and committed there, then the
build that uses it commits the submodule's new commit.

## Building it

Java 25, which Gradle downloads if it's missing. The build needs no robot program and no GradleRIO:
it declares the WPILib and AdvantageKit libraries it compiles against itself, and its tests run
without WPILib's native libraries. It does need the robot model, its submodule: clone with
`--recurse-submodules`, or run `git submodule update --init`.

| Command | Does |
|---|---|
| `./gradlew ci` | Everything CI checks: formatting (Spotless), static analysis (Error Prone, NullAway), the tests, coverage (JaCoCo, floors in `coverage-floor.properties`), the Javadoc; and the model's own `ci`. |
| `./gradlew test --tests '*PitwallServerTest'` | Some of the tests. |
| `./gradlew test -Pquick --continuous` | The fast edit-test loop: skips formatting, static analysis and coverage. |

The tests of the classes that need WPILib's native HAL or AdvantageKit's running logger (`Pitwall`,
`AdvantageKitLog`, `simulator/WpilibHal`) run in the robot program that uses Pitwall, not here:
those three files are left out of the per-file coverage floor. `AdvantageKitLog`'s receiver is
tested here all the same, on AdvantageKit's own table (`robot/AdvantageKitReceiverTest`).

The tests read the configuration records' documentation as files in
`src/test/resources/META-INF/pitwall/docs`, which `ParameterDocsTest` says how to write again.

## The Robot view

The robot's joints as its model says them, live: one card for each joint, drawn by its kind, with
the same facts under it in words. It is drawn from the robot program's own log, so it shows what
the program read and decided, loop by loop, and it is the same on a robot and in the simulator.

A robot program makes the robot's session before its log starts (`RobotSession.of(definition)`),
hands AdvantageKit a receiver for it (`AdvantageKitLog.receiver(session)`, beside the log's file and
its stream), and starts Pitwall with it (`Pitwall.start(name, session)`). The runtime's `Robot`
does all three.

Without a session the page has no Robot view. It needs AdvantageKit: the session is filled by
AdvantageKit's log, and by nothing else.

### What a card shows

| A joint that | Is drawn as |
|---|---|
| turns between stops and holds positions (a pivot, an arm) | an **arc**, its travel between its hard stops, turned the way the joint turns: seen from the robot's side, front to the right, from where its link points at zero |
| turns a whole turn or more and holds positions (a turret) | a **full circle** |
| is run at speeds (a roller, a flywheel) | a **speedometer**, from nothing at the left to its free speed at the right; with an output that runs it backward, nothing is at the top and the needle swings either way |
| slides (an elevator, a slide) | a **straight bar**, upright for one that slides up |

On each: a tick for every named place or output, with its name and value ("out 130°", "intaking
2000 rpm"); the soft limits, where they are inside the stops; a band for each keep-out's region; a
solid needle where the joint is, a dashed one where it was told to go, and a small ring where its
profile has it. Under it: where it is and between which places, where it was told to go, who owns
it (a command, or idle and what it does idle), how its last move ended, what holds it, and how its
motor controller, its absolute encoder and its limit switches are. Beside the joints: the robot's
other sensors, its gates with what each waits for, the gamepads, and the bindings that fired in
the last second.

### Cause, across cards

An interlock on one joint whose condition is about another is drawn on **both** cards. The roller
that runs only when the pivot is at least 110° has, on the pivot's card, a mark from 110° up, named
"◆ roller". A keep-out is a band on its own joint's card, and its condition a mark on the card of
the joint it depends on. Each is drawn by how it stands, and says so in words in the card's list:

| | Run-only-when | Keep-out |
|---|---|---|
| quiet: thin and dotted | met | open |
| in force: solid | not met, and nothing asks the joint to run | closed |
| waited for: thick, in the color of trouble | it holds the joint short of what a command asked | it holds the joint at its edge |

What holds a joint is the robot's own fact: each joint logs the things that hold it (the model's
`Holder`), and an interlock among them is one of the definition's, by its index. A joint that is
held while nothing asks anything of it is said to be restricted, and held once a command asks. What
the log does not carry, `Causes` reads with the model's own conditions from the loop's positions
and sensors, as the runtime does: where on the other joint's card a condition holds, and whether a
keep-out's region is closed while it holds nothing (a joint that doesn't know where it is, or a
lost sensor, reads as unknown, and restricts).

### From the log to the page

- **A loop's record.** AdvantageKit hands every receiver a copy of the whole log table at the end
  of each loop, on a thread of its own. `AdvantageKitLog.receiver(session)` is one. The only class
  that names AdvantageKit is still `AdvantageKitLog`; the session reads a `LoopRecord`, which a
  test fills by hand.
- **The keys** are the runtime's, and `robot/LogKeys` is the one place they are written: the
  contract between the two, which the runtime's `RobotViewKeysEndToEndTest` holds a real session's
  log to. What the robot did is logged as facts, the model's records
  (`com.michaelgrundvig.frc.world.record`): where a joint stands, who asked it, where it is among
  its places, what holds it, how its last move ended, a gate's terms, a binding that fired. Every
  sentence the page shows of them is made by the model's own functions (`JointSentences`,
  `MoveSentences`, `ControlSentences`), so the page and the log's readers say the same words.
- **A moment** is what the view shows of one loop: the time, the mode, each joint's readings and
  what it was told, each sensor, each gate with what blocks it, the bindings that fired, and the
  gamepads. Taking one reads about two hundred values and allocates nothing: the robot writes a
  fact only when it changes, and a fact is made out of the log's bytes only then.
- **The session's memory** is a ring of the last five minutes of moments, in arrays taken once: 810
  bytes a loop for a robot with eight joints and two sensors, 12 MB in all. A fact's fields are
  kept each in its place; texts, and the outcomes of moves, are kept once each in tables of their
  own and known by a number. It keeps at most three things holding a joint, 16 gates with two
  blocking terms each, four fired bindings a loop, two gamepads, 4096 different texts and 1024
  outcomes of moves; past those it keeps the first.
- **The view** is a function of the robot's definition and one moment (`RobotView.of`), so any loop
  the session holds is drawn by the same code as the newest.
- **Live,** the newest loop's view goes to the pages that show it, as the `robot` view, at most 20
  times a second and only what changed, at `robotView.live` in the page's state (`RobotWire` says
  its shape). With no page showing it, the robot loop does nothing for it.
- **Any loop:** `GET /api/robot/view` answers with the newest, `?index=` a loop by its index, and
  `?at=` the loop nearest a time on the robot's clock, in nanoseconds.

### The page

`page/robot.js` is the view and `page/robot-draw.js` draws a joint, in SVG, with no library. The
drawing module imports nothing and knows nothing of the page, so it draws a saved moment as well as
the live one. Labels are placed beside what they name and then moved apart until none overlaps: a
place at a stop ("in 1.5°" at the 0° stop) keeps its spot, and the stop's value moves beside it.

### Planned, and not built

A **session timeline**: a cursor over the remembered five minutes, to scrub and step through, with
an event strip, and the World view following the same cursor. The session, its indexes and the
route that answers for any loop are there for it; the page shows only the newest loop today.

Opening a saved log file stays AdvantageScope's job, as charts do.

## Scenes: the World view

A **scene** is the world drawn in 3D on the page, from what the robot program says is there: a
field, the robot on it, what its cameras see. A scene knows nothing of fields or robots: it
is named frames and shapes in them, grouped into layers the page can switch on and off,
and the page draws every shape itself. With no scene added, the page has no World view. A robot
program adds its scenes to `pitwall.scenes()`, as Pitwall's own `ScenesTest` does:

```java
scenes.add(
    Scene.named("world", "World")
        .layer(field) // describes once: walls, tags
        .layer(robotLayer) // describes, then updates while a page shows the scene
        .layer(rays, Scene.Visibility.OFF) // off until switched on, on the page
        .focus("robot")); // the frame the page's Follow keeps in view
```

### A layer

```java fragment
interface Layer {
  String id(); String title();
  void describe(SceneBuilder scene);          // what stays: frames, values, shapes
  default void update(SceneState state) {}    // what moves: in the robot loop, while a page shows it
  default Optional<Inspection> inspect(String shapeId) { ... }  // a click, on a server thread
  default Object configuration() { ... }      // describe again when this changes (by equals)
}
```

`Layer.of(id, title, describe)` makes one that only describes.

- **`describe`** runs when the scene is added, and again only when the layer's `configuration()`
  changes. The whole scene is checked then: a frame whose parent isn't a frame, two shapes with one
  id, a shape in a frame the scene doesn't have, or a value it doesn't declare, stops the program
  with what's wrong, as a configuration mistake does.
- **`update`** runs every loop while a page shows the scene, and never while none does. It says
  what moved, and keeps it to a handful of numbers: everything said is kept until it's said again.
  A layer that throws costs only itself, and what it threw is printed once.
- **`inspect`** is for a layer that answers clicks itself. Most don't need it: a shape made with
  `.inspect(() -> Inspection.of(...))` answers for itself.

### Frames, values, and shapes

**Frames** are named, each with a parent and a pose in it; the root is `field`. A layer may draw in
any frame another layer of its scene adds. Lengths are meters and angles radians, turned as WPILib
turns (roll about x, then pitch about y, then yaw about z):

```java fragment
scene
    .frame("robot", "field") // moved by update
    .frame(
        "camera",
        "robot",
        new Transform3d(0.3, 0, 0.5, new Rotation3d(0, Math.toRadians(-10), 0))) // fixed, here
    .value("speed", 0.05); // a size that follows a number
...
scene.frame("estimate", "field").deadband("estimate", 0.01, Math.toRadians(0.5)); // "Quiet when still"
```

**Shapes**, each with an id unique in its scene, in a frame, set up by name:

| Shape | What it is |
|---|---|
| `box` | a box centered where it's put: `size(x, y, z)`; filled faintly, `outline()` for edges only |
| `cylinder` | along its z: `radius`, or `radii(x, y)`, or radii that follow two values (an ellipse); `length` 0 makes a disc or, with `outline()`, a ring |
| `line` | `from(...).to(...)` in its frame, or `between(frameA, frameB)`, kept between two frames' origins as they move |
| `polyline` | points, `closed()` or not |
| `polygon` | a flat polygon in its frame's xy plane, filled faintly |
| `mesh` | vertices and triangles: anything else, such as a ramp |
| `arrow` | `from(...).to(...)`, or a `length` that follows a value (a wheel's speed; negative points back) |
| `frustum` | what a camera sees, along its frame's x: `fov(horizontal, vertical)`, `range(near, far)` |
| `label` | a few words, the same size on screen at any zoom: a tag's number, a camera's name |

Each takes `at(...)` (where in its frame, turned or not), `style(...)`, `inspect(...)`, and
`reveal(ids...)`.

**Styles** are the page's color tokens by meaning, never colors: `Style.OK`, `BAD`, `MUTED`,
`ACCENT`, `TRUTH`, each `.dashed()` or not. Color never carries meaning alone: a dashed line is
something not to trust as drawn (a rejected measurement, a bound, the simulator's truth). The page
takes each token from its theme, light or dark.

**In `update`** a layer says, through `SceneState`: a frame's pose (`pose(name, x, y, yaw)`, or a
`Pose2d` or `Pose3d`), a value, a shape's style (`style(id, Style.BAD)`, or `null` to restore the
described one), and its shapes of the moment: `state.shapes()` starts them over, and the shapes
added replace the last ones whole. A layer that doesn't call it keeps what it has, so call it only
when something changed.

**A click** shows the shape's `Inspection` beside the drawing: a title, labeled details, and the
names of page actions (`pitwall.actions()`) it ends in, run exactly as from Pit, gate and all. The
supplier runs on a web server thread, so it reads only what can't change under it, such as a record
the loop has finished with. A shape of the moment stays inspectable for two seconds after it's
gone, so with the page's *Hold* (which freezes the drawing) a sight line that came and went can
still be clicked. While its details are open, the shape clicked and the shapes it `reveal`s are
drawn whatever their layers: a camera reveals its full view, kept in a layer that starts off. What
it reveals must be in the scene's description, or of the same moment.

### What it costs, and the wire

- **The description** goes in the page's first snapshot, as the `scenes` section (`{id: {"title",
  "focus", "layers": {id: {"title", "shown", "frames", "shapes"}}}}`), and again only when a layer is
  described again. Numbers go to the millimeter and the tenth of a degree.
- **What moves** goes only to the pages that show the scene. The page names it on its event stream
  (`/api/events?views=world`, and `PUT /api/views {"views": [...]}` as it changes or its tab is
  hidden). A page that starts showing it first gets a `replace` event with all of it, then JSON
  merge patches at `scenes.<id>.state`: `{"frames": {name: pose}, "values": {name: number},
  "styles": {shape: style}, "shapes": {layer: [shape, ...]}}`. A pose is `[x, y, yaw]` when level on
  the floor, else `[x, y, z, roll, pitch, yaw]`, in meters and degrees.
- **Quiet when still:** a frame is sent only once it has moved its deadband from what was last sent
  (by default the resolution, a millimeter and a tenth of a degree), a value its resolution. An
  estimate that wanders a few millimeters while the robot stands still takes a wider deadband, so
  a still robot sends nothing.
- **At most 20 patches a second,** each holding only what changed, serialized off the robot loop
  and shared by every page showing the scene.
- **Click-to-inspect** is `GET /api/scenes/<id>/inspect/<shape>`, answered from what the loop
  published; 404 once nothing is known of the shape.
- **With no page open it costs nothing:** the loop doesn't call `update`, or anything else of a
  scene.

Measured by `SceneStreamTest` (a robot driving on a field with 32 tags) and, for the template's
own robot, its `WorldViewStreamSimTest`; the design's limit is 250 kbps.

### The page

`page/world.js` is the view; `page/scene-render.js` and `page/scene-shapes.js` draw with three.js,
which loads the first time the view is shown. One renderer, two cameras: *Top*, orthographic (the
2D map), and *3D*, orbiting. *Fit* frames what stays put, *Follow* keeps the scene's focus frame
in view, *Hold* freezes the drawing. A click picks the nearest inspectable line within 12 px, so a
thin ray is easy to hit, else the solid under the pointer. It draws only when a patch arrives or
something is dragged, never in a loop. A browser without WebGL 2 is told so, and the rest of the
page works.

**three.js** comes from its WebJar on Maven Central (`gradle/libs.versions.toml`). The build
(`vendorThree` in `build.gradle`) takes three files out of it, its own minified build and the
orbit controls, into the jar, served at `/three/`. The orbit controls import three.js by the bare
name `'three'`, which a browser resolves only through an import map, and an import map is inline
script, which the page's Content-Security-Policy (`default-src 'self'`) refuses: so the copy
imports `./three.module.min.js` instead, and the build fails if that line stops matching, or if
any file it copies imports anything by a bare name (an update adding `'three/addons/...'`, say). Nothing
here writes a `style` attribute: the stylesheet sizes the canvas, and labels are textures drawn on
a canvas. three.js's license is in [`licenses/`](licenses/three.js-License.md).

### Not building

The World view draws the code's beliefs, to act on: no log loading, CAD, game pieces, or charts.
AdvantageScope does those. It shows the present only, for now: the session timeline that is planned
for the Robot view (above) will move the World view with it.

## The template's World

The template contributes its world from `frc.robot.views.world` (`WorldView.scene`, added in
`RobotContainer.addTo`), and `./gradlew run` shows it at http://localhost:5820/#world:

- **Field:** the field model's walls and carpet, its raised ground (bumps, ramps, platforms), and
  its AprilTags where the layout puts them, each with its number.
- **Robot:** at the localization estimate, on the ground under it and tilted as its IMU says, as
  its `RobotDefinition` describes it: its frame and bumpers from `Body` with an arrow out the
  front; each wheel at its module, turned to its measured angle, with an arrow as far as it will go
  in 0.2 s; each camera at its configured mount, an arrow along its optical axis and the first
  meter of its view, its name beside it, off the robot; and the gyro's IMU, a small block where its
  `ImuMount` puts it, turned as it's mounted, with an arrow along its own x axis. A click on a
  camera says how it's mounted, offers its calibration, and shows its full view until the details
  close; a click on the IMU says how it's mounted and whether its accelerometer has been checked. A
  wedge pointing the wrong way is a mistake in `Robots.java`, found at a glance.
- **Camera views,** off at first: every camera's full view, out as far as it reads tags (its
  sensor's `sightRange`, 7 m on the template's robot).
- **Uncertainty (2σ):** the estimate's position uncertainty as a dashed ellipse, two standard
  deviations across; in the color of trouble while the estimate is lost or the gyro is.
- **Sight lines:** from each camera to the tags its measurements used, solid where the estimator
  accepted the measurement and dashed where it refused it. A click says why: the verdict, the
  innovation, χ², the distance, the measured pose, and the solve, and offers the camera's
  calibration.
- **Truth:** in the simulator only, where the robot really is, as a dashed ghost beside the
  estimate.
