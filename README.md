# Pitwall

Pitwall is the robot program's own web page: status, actions, live tuning, experiments, devices,
the robot's joints drawn live with a timeline of the last five minutes, and the simulator's
controls, served by the program itself on port 5820 (http://localhost:5820 in the simulator). It's
a library of its own
(`com.michaelgrundvig.frc.pitwall`), free of any team's robot code. It reads the robot model
(`com.michaelgrundvig.frc:frc-robot-model`): handed a robot's definition, it draws that robot's
joints as the model says them, and its world on the field. Everything else the robot program
contributes to it, as data. The robot template's README describes each part; this file is for
contributors, and covers the simulator's controls as they reach every view, the page's keys, its
Robot view, and its scenes: the World view.

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

## The simulator's controls, on every view

The World and Robot views are where a simulated robot is watched, so its controls are reachable
from them: nobody leaves the view they are watching to enable, drive, run an auto or fail a part.
All of it is simulator only, and one check decides that for the whole page: `sim-state.js`'s
`isSimulator()`, true for a simulated robot whose state has a `simulator` section. A robot's page
has no such section and none of the routes (they answer 404), so none of these controls is built.

- **Enable and Disable in the state bar**, beside the mode, on every view (`sim-enable.js`, the
  same control as the *Sim* view's bar, small). Enable is a button, pressed: no key enables, and
  nothing enables as a side effect. Pressed, it hands the focus to Disable, so a Space or an Enter
  meant for something else can't enable the robot again once it's disabled. On a robot the bar shows the mode and has nothing to press
  about it; the Driver Station enables a robot.
- **The side dock** (`side-dock.js`) is a place beside every view that the page owns. A panel
  registers with `addPanel({id, title, label, offered(), mount(element), show(), hide()})` and the
  dock gives it a button in the state bar, opens and closes it, sets its width (drag its edge, or
  the arrow keys with the edge focused), and remembers in this browser which panel was open and how
  wide. One panel shows at a time. On a wide screen the dock is a column and the view reflows
  beside it: a view's breakpoints ask the views' own width (`@container views`), not the window's.
  Under 900 px it lies over the bottom half of the page and closes with one press. The Sim panel
  is its first panel; a second (a compact Robot view, say) is one more `addPanel`.
- **The Sim panel** (`sim-panel.js`) is the simulator's controls, compact, in the order they are
  used while watching: the mode and alliance station, driving, the auto to run, the simulator's
  clock, and what fails. It mounts the very modules the *Sim* view mounts, each with `{compact:
  true}`: `sim-station.js`, `sim-driving.js`, `sim-auto.js`, `sim-time.js`, `sim-faults.js`. A
  section is a function that returns `{element, render(what)}`, so it can be mounted in more than
  one place, and `keepDrawn` draws it only while its place is on screen. There is one of each
  control, never a copy.
- **Driving from any view:** keyboard driving, once switched on, drives whatever view is showing
  (`sim-drive.js` listens on the page, not on a view). The state bar says "⌨ Keys drive the robot"
  on every view while it does, or why it isn't driving now, with Switch off beside it.
- **The auto to run** (`sim-auto.js`): the robot program marks the device whose card is what
  autonomous runs (`Devices.Device.autonomous()`), and the panel shows that card's lines and its
  chooser (the one Pit's card has) over one button, *Set autonomous and enable*, which sends
  `{"mode": "AUTONOMOUS", "enabled": true}` and says so before it is pressed. Choosing never
  enables.
- **What fails** (`sim-faults.js`): one row draws a fault wherever it is listed. The *Sim* view
  lists them as a card for each part; the panel as one short list, those on now first and then
  those used lately (kept in this browser), eight until asked for all; both have a filter.
- **Faults from a click:** in the World view an `Inspection` may name the simulator's parts the
  shape is (`Inspection.component(name)`), and the inspect route answers with those the simulator
  has (`"components"`, always empty on a robot). The details panel then ends in those parts'
  faults, the same rows. `WorldScene.of(..., SimulatedParts)` is how a robot's simulation says what
  it called each part: a camera, the gyro, a module (a wheel), and on the bumpers the robot itself
  with the backup IMU, which has no shape. A fault two of whose numbers are a place on the field
  (`Fault.Builder.point(x, y)`: setting the robot down) gets *Apply where I click*, which takes
  the place from the next click on the drawing.

### The page's keys

Esc is the one key several things want, and one of them is safety, so its rule is in one place
(`page/keys.js`):

| Key | Where | What it does |
|---|---|---|
| Esc | any view, any focus, in the simulator with the robot enabled | Disables the robot and stops whatever runs. Nothing else hears it: not a field, not the time strip, not an open dialog |
| Esc | any view, while a run or an action is running | Stops it |
| Esc | in a value's field, otherwise | Drops the edit |
| Esc | otherwise, with a time strip on screen | Returns the cursor to live |
| P | any view, not in a field | Opens and closes the side dock's panel |
| W A S D, J L, ← →, R | any view, not in a field, while keyboard driving is on | Drive joystick 0 |
| ← →, Shift+← →, Space, L | a view with a time strip, not in a field, while keyboard driving is off | A loop, an event, play and pause, back to live |
| Home, End, PageUp, PageDown | the time strip's track, focused | The oldest loop, live, a second later or earlier |
| ← → | the side dock's edge, focused | Wider, narrower |
| / | Tune, not in a field | Focuses the filter |
| Enter, ? | in a value's field | Applies it, shows its notes |

A safety action wins over a view's action, so on an enabled simulated robot Esc disables from any
view, and only a disabled robot's Esc means anything else. The driving keys and the strip's arrows
and L never meet: the strip stands aside for as long as keyboard driving is on. No key enables.
The page has no JavaScript test harness, so these rules are checked in a browser.

## The Robot view

The robot's joints as its model says them, live: one card for each joint, drawn by its kind, with
the same facts under it in words. It is drawn from the robot program's own log, so it shows what
the program read and decided, loop by loop, and it is the same on a robot and in the simulator.
Along its bottom is a time strip over the last five minutes: live is too fast to see the order
things happened in, so the page scrubs back through what was recorded, loop by loop, with what
happened listed in order beside it ([The session timeline](#the-session-timeline)).

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
the last second, each with the control that fired it marked on its gamepad.

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
- **Where the robot was** is in the moment too, for the World view (below): the estimated pose
  with how sure it was and what it rested on, the true pose in the simulator, the wheels, and each
  camera's tags of the loop with what became of each. A pose, a covariance and the wheels'
  velocities are structs in the log, made of numbers alone, and are read as those numbers where
  they lie (`LoopRecord.numbers`), since they change every loop. A camera's tags are facts
  (`robot/Sighting`), written when they change, with their measurements beside them as a list of
  numbers. A verdict and a solver are numbers in the log; their words are the robot program's
  own, handed to the session as it is made (`SightingWords`), so nothing here words one again.
- **The session's memory** is a ring of the last five minutes of moments, in arrays taken once:
  1500 bytes a loop for a robot with eight joints, two sensors and four cameras, 22.5 MB in all.
  Of that, where the robot was is 130 bytes a loop and 138 more for each camera, which keeps four
  of a camera's tags a loop and counts the rest. A fact's fields are
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
  `?at=` the loop nearest a time on the robot's clock, in nanoseconds. With a loop comes what
  changed in it from the loop before (`Changed`, a function of the definition and two moments).

### The session timeline

The session records all the time: its memory is the recording. What the page shows of it is one
piece of page state, the **cursor** (`page/cursor.js`), which is the page's and no view's: either
live, pinned to the newest loop, or standing on one loop of the session, by its index. Scrubbing
moves what the page shows through what was recorded. It never rewinds the robot or the simulator,
the page still follows the robot meanwhile, and the state bar still says how the robot is now.

- **Events** are the loops in which something a person follows changed from the loop before, each
  with what changed, in words: the robot enabled or disabled, or its mode changed; a binding fired;
  a command took a joint or left it idle; a joint was asked for another place or output by name; a
  move ended, with how; something began or stopped holding a joint short of what it was asked; a
  joint's state changed (homing, faulted, ready); a joint or its absolute encoder stopped
  answering; a gate began to hold or fell, with why; a switch tripped or cleared, or a sensor was
  lost. And where the robot is: it was placed; a camera's measurement was refused, with its
  verdict (one event for a run of them: the next comes after a second without one); what the
  estimate rests on changed (tracking, dead reckoning, lost); the estimate recovered, jumping to
  agree with its cameras, with how far. A move that timed out or was refused, a fault, anything
  lost, an estimate that is lost and a recovery are flagged as problems.
  `EventFinder` finds them as each loop is taken, on the log's thread, by comparing the loop with
  the one before where the memory keeps them: it allocates nothing, and adds about half a
  microsecond a loop. An event is kept as two numbers, 12 bytes, in a ring of 4096 (49 KB), and
  goes when the loop before its own leaves the memory; its sentence is made when it is asked for,
  from its loop's moment, by the model's functions (`EventSentences`). So the events are a function
  of the session's moments and nothing else: found again from the loops still remembered, they are
  the same (`RobotEventsTest`).
- **Asking for them:** `GET /api/robot/events` answers with those the session holds, `?from=&to=`
  in a range of loops, `?fromAt=&toAt=` of times, `?newest=` the last so many, `?before=` and
  `?after=` the one event before or after a loop, each of them `?kind=` of some kinds, `?about=`
  about one joint, sensor, gate or gamepad port, or `?problems=true`. In Java, `session.events()`.
- **The time strip** (`page/timeline.js`), along the bottom of the Robot view and made for any view:
  the memory as a strip of time with the cursor on it and a mark for each event. Marks that would
  touch are one mark with their count, and a problem among them always shows. Drag to scrub, click
  a mark to go to it, go to the previous or next event, an earlier or later loop, play at 1×, 0.5×,
  0.25× or 0.1×, show the last 10 s, 30 s, 2 min or all of it, and go back to live. It says how far
  behind live the cursor is, and when the cursor's loop leaves the memory the cursor moves to the
  oldest loop held and the strip says so.
- **Keys**, while the strip is on screen: Left and Right go one loop, with Shift one event; Space
  plays and pauses; Esc or L goes back to live. They stand aside for a field with the focus, for
  keyboard driving (which drives with the arrows and L), and for Esc while a simulated robot is
  enabled or something runs, which disables or stops it ([the page's keys](#the-pages-keys)). None
  of them reaches the robot.
- **The list of events** (`page/event-list.js`), beside the strip: the events around the cursor in
  order, with their times, those of the cursor's loop marked; a click moves the cursor there. A
  joint's name on its card shows that joint's events only, and *Problems only* the problems. This is
  where an ordering mistake is read: "feeder told feeding" above "shooter at goal holds" is a shot
  that fed before its shooter was up.
- **What changed in this loop:** while the cursor stands on a loop, the rows that changed in it
  from the loop before are marked on the cards, so going loop by loop shows a cause and then its
  effect. A row is marked when what it says changed, and not when a number only moved.
- **The simulator's pause and step** are another thing. *Step one new loop*, under *Simulator
  clock* in the Sim view and the Sim panel, runs one new loop of a paused simulator, which adds a
  moment; the strip's *Loop* buttons go through loops that already ran. While the simulator is
  paused, live stands still, and the strip says so.
- **Other views:** a view reads the cursor (`cursor()`), hears when it moves (`onCursor`), and
  draws its own data for that loop. The World view does (below), with the same strip and list of
  events along its bottom and the same keys, so flipping between the two views keeps the moment.

A loop is known by its index; when it was is the robot's to say, since loops are not evenly spaced
(a slow first loop, an overrun). The page works out a loop's time between the nearest loops whose
times it was told: the memory's two ends, every event, every loop it has shown.

### The page

`page/robot.js` is the view and `page/robot-draw.js` draws a joint, in SVG, with no library. The
drawing module imports nothing and knows nothing of the page, so it draws any loop the same way.
Labels are placed beside what they name and then moved apart until none overlaps: a place at a stop
("in 1.5°" at the 0° stop) keeps its spot, and the stop's value moves beside it. The view draws the
loop the cursor is on: the newest as it arrives, or an earlier one asked of the robot and kept
(`page/robot-moments.js` keeps a few hundred, the latest asked for, and fetches only the loop the
cursor is on now, so a drag across a thousand loops asks for a handful; `page/loop-store.js` is
that keeping, which the World view uses for its own loops).

### Not built

**Recording a test's run** to look at here. A **trail** of the estimated and the true pose over the
strip's window, in the World view. Opening a saved log file stays AdvantageScope's job, as charts do.

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
  default Optional<Recorded> recorded() { ... } // the same of an earlier loop, from a record
  default List<Legend> legend() { ... }       // what its marks mean, for the page's legend
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
- **`recorded`** is for a layer drawn from the robot's record: it says what `update` said in any
  loop the record holds, whole, and what a click on one of its shapes says of that loop. The page
  then draws the scene at its cursor (`GET /api/scenes/<id>/state?index=`, and
  `.../inspect/<shape>?index=`), from the web server's thread and at no cost to the robot loop. A
  layer without it is drawn live only: at an earlier loop the page hides it and says so.
  `Layer.Recorded.STILL` is for a layer in which nothing moves.
- **`legend`** says what each kind of the layer's marks means, each with the `Style` it is drawn
  in. The page's legend lists the layers, is where each is switched on and off, and under a shown
  layer draws a sample of each mark from that same style, so a sample cannot differ from the
  drawing. What is switched is kept in the browser.

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
own robot, the runtime's `WorldViewFromTheRecordEndToEndTest` (a 20 kB description, and 11 kbps
while driving); the design's limit is 250 kbps.

### The page

`page/world.js` is the view; `page/scene-render.js` and `page/scene-shapes.js` draw with three.js,
which loads the first time the view is shown. One renderer, two cameras: *Top*, orthographic (the
2D map), and *3D*, orbiting. *Fit* frames what stays put, *Follow* keeps the scene's focus frame
in view. The view draws the moment the page's cursor is on: live, the robot's patches; at an
earlier loop, that loop's state asked of the robot and kept. To hold the drawing still, stand the
cursor on a loop, with the strip under the drawing or the Left key; there is no *Hold* button any
more. Beside the drawing is the legend, which folds to one button. A click picks the nearest
inspectable line within 12 px, so a thin ray is easy to hit, else the solid under the pointer, and
asks about the loop shown; the pointer over a shape shows what it reveals (a camera's name and
view) while it is there. It draws only when a patch arrives or
something is dragged, never in a loop.

Labels are a fixed size on screen, so zoomed out they crowd what they name. Each drawing lays them
out on the screen (`layoutLabels`): a label in the focus's own frame (the robot's: its cameras'
names) moves straight out from the robot's middle, just far enough to clear the robot's outline
and any label placed before it, with a thin leader back to where it was once it has moved more
than a few pixels; every other label (a tag's number) stays put and fades by how far the robot or
one of its labels lies over it. Both are worked out from where things are on screen and nothing
else, so a label moves with the robot and never jumps or blinks. A browser without WebGL 2 is told so, and the rest of the
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
AdvantageScope does those. It reaches back as far as the session's memory does, five minutes, and
no further.

## The World: `world/WorldScene`

The robot's world is Pitwall's own scene, drawn from the robot's record: `WorldScene.of(definition,
field, truth, words, cameraAction).scene(session)`, which the runtime adds in
`RobotContainer.addTo`, and `./gradlew run` shows at http://localhost:5820/#world. What moves is a
function of the robot's definition, the field and one loop's moment (`WorldScene.draw(moment)`), as
the Robot view is of the definition and a moment. So it is one implementation at any loop: live, a
layer draws the session's newest moment in the robot loop, and at the cursor the same function
draws the loop asked for.

Each of these is a layer the legend switches:

- **Field:** the field model's walls and carpet, its raised ground (bumps, ramps, platforms), and
  its AprilTags where the layout puts them. **Tag numbers** are a layer of their own.
- **Robot:** at the estimate (`Localization/Pose3d`), on the ground under it and tilted as its IMU
  said, as its `RobotDefinition` describes it: its frame and bumpers from `Body` with an arrow out
  the front; each wheel at its module, turned to its measured angle; each camera as a short arrow
  along its optical axis at its mount; and the gyro's IMU, a small block where its `ImuMount` puts
  it, with an arrow along its own x axis. A camera or a sensor that rides on a joint is drawn where
  the joint was in that loop. A click on a camera says how it's mounted, offers its calibration,
  and shows its name and its view until the details close; the pointer over it does the same while
  it is there.
- **Wheel arrows:** where each wheel is driving, as far as it will go in 0.2 s.
- **Sensors:** each switch and rangefinder as a small block, and a rangefinder's cone.
- **Camera names** and **Camera views,** both off at first: four of each crowd the robot. A view
  is its edges only, out to 3 m, though a camera reads tags farther than that.
- **Uncertainty (2σ):** the estimate's covariance (`Localization/Covariance`) as a dashed ellipse,
  two standard deviations across; in the color of trouble while the estimate is lost or the gyro
  is. A click says how sure, what it rests on, and how the cameras have fared.
- **Sight lines:** from each camera to the tags it measured (`Vision/<camera>/Sightings`), solid
  where the estimator accepted the measurement and dashed where it refused it, kept for 300 ms
  after the measurement so a line does not flicker between images. A click says why: the verdict
  in localization's own words, the innovation, χ², the distance, the measured pose, and the solve,
  and offers the camera's calibration.
- **True pose:** in the simulator only (`Sim/TruePose3d`), where the robot really is, as a dashed
  ghost beside the estimate.

`WorldSceneTest` holds the function to moments taken from records made by hand. The runtime's
`WorldViewFromTheRecordEndToEndTest` holds it to a real session: for every loop, what is drawn from
the log is what the robot's own objects said in that loop.

