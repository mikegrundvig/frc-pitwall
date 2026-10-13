/**
 * The robot as its model says it, live: the page's Robot view. One card for each joint, drawn by
 * its kind, showing where it is, what it was told, and what holds it.
 *
 * <p>The data is the robot program's own log. Every loop, AdvantageKit hands over that loop's
 * record ({@link com.michaelgrundvig.frc.pitwall.robot.LoopRecord}); {@link
 * com.michaelgrundvig.frc.pitwall.robot.Extraction} takes from it a compact {@link
 * com.michaelgrundvig.frc.pitwall.robot.Moment}, which {@link
 * com.michaelgrundvig.frc.pitwall.robot.SessionMemory} keeps for the last few minutes.
 *
 * <p>The keys it reads are the runtime's ({@link com.michaelgrundvig.frc.pitwall.robot.LogKeys}).
 * Nothing here names AdvantageKit: a robot program without it has no record, and no Robot view.
 */
package com.michaelgrundvig.frc.pitwall.robot;
