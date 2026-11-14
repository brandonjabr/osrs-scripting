package net.runelite.client.plugins.microbot.util.mouse.naturalmouse.support;

import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.api.DeviationProvider;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.api.MouseInfoAccessor;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.api.MouseMotionFactory;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.api.SystemCalls;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.util.FactoryTemplates;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Runs whole movements through {@code MouseMotion} on a fake 765x503 canvas and measures how far each path bows
 * from its straight line. Also draws them to {@code build/mouse-paths.png}: the new arcs on the left, the old
 * sinusoidal deviation on the right.
 */
public class HumanArcDeviationProviderTest {

    private static final Dimension SCREEN = new Dimension(765, 503);
    // A right hand pivoting below-right of the screen.
    private static final HumanArcDeviationProvider.Persona RIGHT_HAND =
            new HumanArcDeviationProvider.Persona(false, 0.9, 2.0, 0.085);

    /** A fake cursor: moves are recorded, sleeps only advance a clock. */
    private static final class FakeCursor implements SystemCalls, MouseInfoAccessor {
        Point position = new Point();
        long now;
        final List<Point> trail = new ArrayList<>();

        @Override
        public long currentTimeMillis() {
            return now;
        }

        @Override
        public void sleep(long time) {
            now += time;
        }

        @Override
        public Dimension getScreenSize() {
            return SCREEN;
        }

        @Override
        public void setMousePosition(int x, int y) {
            position = new Point(x, y);
            trail.add(position);
        }

        @Override
        public Point getMousePosition() {
            return position;
        }
    }

    /** Peak sideways offset as a share of the distance, where along the move it was, and which side. */
    private static final class Bow {
        double share;
        double peakAt;
        int side;
    }

    private static MouseMotionFactory factory(DeviationProvider deviation, FakeCursor cursor, long seed) {
        Rs2AntibanSettings.simulateFatigue = false;
        Rs2AntibanSettings.simulateMistakes = false;
        DefaultMouseMotionNature nature = new DefaultMouseMotionNature();
        nature.setSystemCalls(cursor);
        nature.setMouseInfo(cursor);
        // The template Microbot uses by default.
        MouseMotionFactory factory = FactoryTemplates.createSuperFastGamerMotionFactory(nature);
        factory.setDeviationProvider(deviation);
        factory.setRandom(new Random(seed));
        return factory;
    }

    private static List<Point> move(MouseMotionFactory factory, FakeCursor cursor, Point from, Point to) throws Exception {
        cursor.position = from;
        cursor.trail.clear();
        cursor.trail.add(from);
        factory.build(to.x, to.y).move();
        return new ArrayList<>(cursor.trail);
    }

    private static Bow bow(List<Point> path, Point from, Point to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double d = Math.hypot(dx, dy);
        Bow bow = new Bow();
        double best = 0;
        for (Point p : path) {
            // Signed distance along (dy, -dx) / d, the provider's positive side.
            double off = ((p.x - from.x) * dy - (p.y - from.y) * dx) / d;
            if (Math.abs(off) > Math.abs(best)) {
                best = off;
                bow.peakAt = ((p.x - from.x) * dx + (p.y - from.y) * dy) / (d * d);
            }
        }
        bow.share = Math.abs(best) / d;
        bow.side = (int) Math.signum(best);
        return bow;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    @Test
    public void curveIsZeroAtBothEndsAndPeaksBeforeTheMiddle() {
        HumanArcDeviationProvider provider = new HumanArcDeviationProvider(RIGHT_HAND);
        Random random = new Random(1);
        for (int i = 0; i < 200; i++) {
            var curve = provider.curveFor(100, 300, 600, 280, SCREEN, random);
            assertEquals(0, curve.offset(0), 1e-9);
            assertEquals(0, curve.offset(1), 1e-9);
            double peak = 0;
            double peakAt = 0;
            for (int s = 1; s < 100; s++) {
                double v = Math.abs(curve.offset(s / 100.0));
                if (v > peak) {
                    peak = v;
                    peakAt = s / 100.0;
                }
            }
            assertTrue("peak at " + peakAt, peakAt >= 0.3 && peakAt <= 0.52);
        }
    }

    @Test
    public void realMovementsLandOnTargetAndBowLikeAHand() throws Exception {
        FakeCursor cursor = new FakeCursor();
        MouseMotionFactory human = factory(new HumanArcDeviationProvider(RIGHT_HAND), cursor, 7);

        int runs = 200;
        double[] shares = new double[runs];
        double[] peaks = new double[runs];
        int upward = 0;
        Random random = new Random(3);
        for (int i = 0; i < runs; i++) {
            // Long sideways moves, left to right across the middle of the screen.
            Point from = new Point(80 + random.nextInt(60), 200 + random.nextInt(100));
            Point to = new Point(560 + random.nextInt(100), 200 + random.nextInt(100));
            List<Point> path = move(human, cursor, from, to);
            assertEquals("lands on target", to, path.get(path.size() - 1));
            Bow b = bow(path, from, to);
            shares[i] = b.share;
            peaks[i] = b.peakAt;
            // Moving right, the positive side (dy, -dx) is up: away from a pivot below the screen.
            if (b.side > 0) upward++;
        }
        double medianShare = median(shares);
        double medianPeak = median(peaks);
        System.out.printf("Human arc: median bow %.1f%% of distance, peak at %.0f%% of the way, %d/%d bowed up%n",
                medianShare * 100, medianPeak * 100, upward, runs);
        assertTrue("visible but modest bow: " + medianShare, medianShare > 0.03 && medianShare < 0.15);
        assertTrue("peaks before the middle: " + medianPeak, medianPeak < 0.5);
        assertTrue("consistent direction for a right hand: " + upward, upward > runs * 0.75);

        // For comparison, the old sinusoidal deviation on the same template and moves.
        MouseMotionFactory legacy = factory(new SinusoidalDeviationProvider(SinusoidalDeviationProvider.DEFAULT_SLOPE_DIVIDER), cursor, 7);
        double[] legacyShares = new double[runs];
        random = new Random(3);
        for (int i = 0; i < runs; i++) {
            Point from = new Point(80 + random.nextInt(60), 200 + random.nextInt(100));
            Point to = new Point(560 + random.nextInt(100), 200 + random.nextInt(100));
            legacyShares[i] = bow(move(legacy, cursor, from, to), from, to).share;
        }
        System.out.printf("Old sinusoidal: median bow %.1f%% of distance%n", median(legacyShares) * 100);
    }

    @Test
    public void drawsSamplePaths() throws Exception {
        int panelW = SCREEN.width;
        BufferedImage image = new BufferedImage(panelW * 2 + 10, SCREEN.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(18, 20, 26));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());

        Point[][] moves = {
                {new Point(80, 250), new Point(680, 250)},
                {new Point(680, 120), new Point(90, 140)},
                {new Point(380, 460), new Point(400, 60)},
                {new Point(100, 450), new Point(650, 70)},
                {new Point(650, 450), new Point(120, 80)},
                {new Point(300, 300), new Point(420, 330)},
        };
        DeviationProvider[] providers = {
                new HumanArcDeviationProvider(RIGHT_HAND),
                new SinusoidalDeviationProvider(SinusoidalDeviationProvider.DEFAULT_SLOPE_DIVIDER)
        };
        String[] labels = {"New: human arc (right hand)", "Old: sinusoidal deviation"};
        FakeCursor cursor = new FakeCursor();
        for (int panel = 0; panel < 2; panel++) {
            int ox = panel * (panelW + 10);
            MouseMotionFactory factory = factory(providers[panel], cursor, 11);
            for (Point[] m : moves) {
                for (int repeat = 0; repeat < 6; repeat++) {
                    List<Point> path = move(factory, cursor, m[0], m[1]);
                    g.setColor(new Color(90, 200, 190, 150));
                    g.setStroke(new BasicStroke(1.4f));
                    for (int i = 1; i < path.size(); i++) {
                        g.drawLine(ox + path.get(i - 1).x, path.get(i - 1).y, ox + path.get(i).x, path.get(i).y);
                    }
                }
                g.setColor(new Color(255, 255, 255, 60));
                g.drawLine(ox + m[0].x, m[0].y, ox + m[1].x, m[1].y);
                g.setColor(new Color(240, 190, 90));
                g.fillOval(ox + m[1].x - 3, m[1].y - 3, 6, 6);
            }
            g.setColor(Color.WHITE);
            g.drawString(labels[panel], ox + 10, 20);
        }
        g.dispose();
        File out = new File("build/mouse-paths.png");
        out.getParentFile().mkdirs();
        ImageIO.write(image, "png", out);
        System.out.println("Wrote " + out.getAbsolutePath());
        assertTrue(out.length() > 0);
    }
}
