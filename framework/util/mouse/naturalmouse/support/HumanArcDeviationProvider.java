package net.runelite.client.plugins.microbot.util.mouse.naturalmouse.support;

import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.api.PathDeviationProvider;

import java.awt.Dimension;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Curves mouse movements the way a hand does. A mouse is moved by rotating the wrist and elbow, so the cursor sweeps
 * part of a circle around a pivot below the screen (below-right for a right hand, below-left for a left hand) instead of
 * following a straight line. That gives human paths three traits this models:
 * <ul>
 *   <li><b>Consistent bend.</b> Paths bow away from the pivot, so the same person curves the same way for the same
 *   direction: sideways moves arc the most, moves toward or away from the hand are nearly straight.</li>
 *   <li><b>Size.</b> The bow grows with the distance, to several percent of it, and short corrections are close to
 *   straight.</li>
 *   <li><b>Asymmetry.</b> The fast first part of a move carries the arm furthest off the line, so the widest point
 *   comes before the middle, and the end is straighter as the hand homes in on the target.</li>
 * </ul>
 * Every move varies around that: its size, where it peaks, and now and then a bend the other way. The hand, the pivot
 * and the overall curviness are picked once per session ({@link Persona}), like a person who keeps their habits.
 */
public class HumanArcDeviationProvider implements PathDeviationProvider {

    // Chance a session uses the left hand, as in the general population.
    private static final double LEFT_HANDED_CHANCE = 0.1;
    // Chance a single move bends the "wrong" way, from a grip change or aiming around something.
    private static final double FLIP_CHANCE = 0.12;
    // Moves shorter than this curve proportionally less.
    private static final double FULL_CURVE_DISTANCE = 90;
    private static final double MAX_OFFSET_PX = 90;
    private static final double MAX_OFFSET_SHARE = 0.25;

    /** One session's hand: where it pivots (as a share of the screen) and how much it curves. */
    public static final class Persona {
        final boolean leftHanded;
        final double pivotX;
        final double pivotY;
        final double strength;

        Persona(boolean leftHanded, double pivotX, double pivotY, double strength) {
            this.leftHanded = leftHanded;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.strength = strength;
        }

        static Persona random(Random random) {
            boolean left = random.nextDouble() < LEFT_HANDED_CHANCE;
            double x = 0.7 + random.nextDouble() * 0.45;
            return new Persona(left, left ? 1 - x : x, 1.5 + random.nextDouble(), 0.06 + random.nextDouble() * 0.05);
        }

        @Override
        public String toString() {
            return String.format("%s hand, pivot (%.2f, %.2f) of screen, strength %.3f",
                    leftHanded ? "left" : "right", pivotX, pivotY, strength);
        }
    }

    private static volatile Persona sessionPersona;

    private final Persona persona;

    /** Uses this session's hand, picked at random the first time. */
    public HumanArcDeviationProvider() {
        this(sessionPersona());
    }

    public HumanArcDeviationProvider(Persona persona) {
        this.persona = persona;
    }

    public static Persona sessionPersona() {
        Persona p = sessionPersona;
        if (p == null) {
            synchronized (HumanArcDeviationProvider.class) {
                if (sessionPersona == null) {
                    sessionPersona = Persona.random(ThreadLocalRandom.current());
                }
                p = sessionPersona;
            }
        }
        return p;
    }

    @Override
    public Curve curveFor(double startX, double startY, double endX, double endY, Dimension screen, Random random) {
        double dx = endX - startX;
        double dy = endY - startY;
        double distance = Math.hypot(dx, dy);
        if (distance < 3 || screen == null) {
            return t -> 0;
        }
        double ux = dx / distance;
        double uy = dy / distance;

        // From the pivot to the middle of the move: the arc bends away from it, most for moves across it.
        double rx = (startX + endX) / 2 - persona.pivotX * screen.width;
        double ry = (startY + endY) / 2 - persona.pivotY * screen.height;
        double r = Math.max(1, Math.hypot(rx, ry));
        double across = Math.abs(ux * ry - uy * rx) / r;
        double directionFactor = 0.25 + 0.75 * across;

        // Positive offsets go along (uy, -ux); pick the sign that points away from the pivot.
        double sign = (uy * rx - ux * ry) >= 0 ? 1 : -1;
        if (random.nextDouble() < FLIP_CHANCE) sign = -sign;

        double sizeFactor = Math.min(1, distance / FULL_CURVE_DISTANCE);
        double variation = Math.max(0.4, Math.min(2.0, Math.exp(random.nextGaussian() * 0.35)));
        double amplitude = distance * persona.strength * directionFactor * sizeFactor * variation;
        amplitude = Math.min(amplitude, Math.min(MAX_OFFSET_PX, distance * MAX_OFFSET_SHARE));

        // t * (1 - t)^b peaks at t = 1 / (1 + b): pick b so that is a bit before the middle. It leaves the start at a
        // finite angle (no hook) and flattens into the target, like a hand homing in.
        double peakAt = 0.33 + random.nextDouble() * 0.17;
        double b = (1 - peakAt) / peakAt;
        double peakValue = peakAt * Math.pow(1 - peakAt, b);
        double scale = sign * amplitude / peakValue;
        return t -> {
            double c = Math.max(0, Math.min(1, t));
            return scale * c * Math.pow(1 - c, b);
        };
    }
}
