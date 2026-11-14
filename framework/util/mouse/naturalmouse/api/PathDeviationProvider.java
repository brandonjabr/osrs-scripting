package net.runelite.client.plugins.microbot.util.mouse.naturalmouse.api;

import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.support.DoublePoint;

import java.awt.Dimension;
import java.util.Random;

/**
 * A {@link DeviationProvider} that bends each movement sideways, relative to its own direction, instead of along the
 * screen axes. {@link MouseMotion} asks it for one {@link Curve} per straight movement (each overshoot correction is
 * its own movement) and offsets every step perpendicular to the line from start to end.
 */
public interface PathDeviationProvider extends DeviationProvider {

    /** How far one movement bows away from its straight line as it progresses. */
    interface Curve {
        /**
         * @param completionFraction how far along the movement is, 0 to 1
         * @return offset in pixels perpendicular to the direction of travel: a positive offset moves the cursor along
         * {@code (dy, -dx) / distance}, where {@code (dx, dy)} is end minus start. Should be 0 at both ends.
         */
        double offset(double completionFraction);
    }

    Curve curveFor(double startX, double startY, double endX, double endY, Dimension screen, Random random);

    /** Not used: {@link MouseMotion} calls {@link #curveFor} for path-relative providers. */
    @Override
    default DoublePoint getDeviation(double totalDistanceInPixels, double completionFraction) {
        return DoublePoint.ZERO;
    }
}
