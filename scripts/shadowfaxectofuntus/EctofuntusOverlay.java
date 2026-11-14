package net.runelite.client.plugins.microbot.shadowfaxectofuntus;

import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.statemachine.StateSnapshot;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.time.Duration;

/**
 * Stats card drawn with Graphics2D: header with live state, session tiles, Prayer progress with time to the next
 * level, and what's left to offer.
 */
public class EctofuntusOverlay extends Overlay {

    private static final int WIDTH = 250;
    private static final int PAD = 12;
    private static final int GAP = 6;
    private static final int HEADER_H = 40;
    private static final int TILE_H = 38;
    private static final int SECTION_H = 20;
    private static final int LINE_H = 15;
    private static final int BAR_H = 12;

    private static final Color CARD_BG = new Color(16, 18, 24, 225);
    private static final Color CARD_BORDER = new Color(255, 255, 255, 30);
    private static final Color TILE_BG = new Color(255, 255, 255, 14);
    private static final Color BAR_BG = new Color(255, 255, 255, 22);
    private static final Color TEXT = new Color(236, 238, 242);
    private static final Color MUTED = new Color(150, 156, 170);
    private static final Color ACCENT = new Color(240, 205, 110);
    private static final Color ACCENT_2 = new Color(230, 150, 70);
    private static final Color GOOD = new Color(120, 200, 110);
    private static final Color SHADOW = new Color(0, 0, 0, 160);

    private final Client client;
    private final EctofuntusConfig config;
    private final EctofuntusStats stats;
    private final EctofuntusScript script;

    @Inject
    EctofuntusOverlay(EctofuntusPlugin plugin, Client client, EctofuntusConfig config,
                       EctofuntusStats stats, EctofuntusScript script) {
        super(plugin);
        this.client = client;
        this.config = config;
        this.stats = stats;
        this.script = script;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.UNDER_WIDGETS);
        setDragTargetable(true);
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (!config.showOverlay()) {
            return null;
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int height = PAD + HEADER_H
                + 2 * (TILE_H + GAP)
                + SECTION_H + LINE_H + BAR_H + LINE_H + GAP
                + SECTION_H + 4 * LINE_H
                + PAD;

        drawCard(g, height);
        int y = PAD;
        y = drawHeader(g, y);
        y = drawTiles(g, y);
        y = drawPrayer(g, y) + GAP;
        drawRun(g, y);
        return new Dimension(WIDTH, height);
    }

    // --- Sections ---

    private void drawCard(Graphics2D g, int height) {
        RoundRectangle2D card = new RoundRectangle2D.Float(0, 0, WIDTH - 1, height - 1, 14, 14);
        g.setColor(CARD_BG);
        g.fill(card);
        g.setColor(CARD_BORDER);
        g.setStroke(new BasicStroke(1f));
        g.draw(card);
        g.setPaint(new GradientPaint(0, 0, ACCENT, WIDTH, 0, ACCENT_2));
        g.fill(new RoundRectangle2D.Float(PAD, 0, WIDTH - 2 * PAD, 3, 3, 3));
    }

    private int drawHeader(Graphics2D g, int y) {
        String state = currentState();
        g.setColor("WORSHIP".equals(state) ? GOOD : ACCENT_2);
        g.fillOval(PAD, y + 5, 8, 8);

        g.setFont(FontManager.getRunescapeBoldFont());
        text(g, "Shadowfax Ectofuntus", PAD + 14, y + 14, TEXT);
        g.setFont(FontManager.getRunescapeSmallFont());
        textRight(g, "v" + EctofuntusPlugin.VERSION, WIDTH - PAD, y + 13, MUTED);

        String status = Microbot.status != null ? Microbot.status : "";
        String line = state.isEmpty() ? status : pretty(state) + "  ·  " + status;
        text(g, ellipsize(g, line, WIDTH - 2 * PAD), PAD, y + 32, MUTED);
        return y + HEADER_H;
    }

    private int drawTiles(Graphics2D g, int y) {
        int cols = 3;
        int tileW = (WIDTH - 2 * PAD - (cols - 1) * GAP) / cols;
        long xpGained = xpGained();
        String[][] tiles = {
                {"RUNTIME", formatDuration(stats.runtime())},
                {"WORSHIPS", formatNumber(stats.worships())},
                {"WORSHIPS / HR", formatNumber(stats.perHour(stats.worships()))},
                {"PRAYER XP", formatNumber(xpGained)},
                {"XP / HR", formatNumber(stats.perHour(xpGained))},
                {"TOKENS", formatNumber(stats.tokensClaimed())},
        };
        for (int i = 0; i < tiles.length; i++) {
            int x = PAD + (i % cols) * (tileW + GAP);
            int ty = y + (i / cols) * (TILE_H + GAP);
            g.setColor(TILE_BG);
            g.fill(new RoundRectangle2D.Float(x, ty, tileW, TILE_H, 8, 8));
            g.setFont(FontManager.getRunescapeSmallFont());
            text(g, ellipsize(g, tiles[i][0], tileW - 12), x + 6, ty + 13, MUTED);
            g.setFont(FontManager.getRunescapeBoldFont());
            text(g, ellipsize(g, tiles[i][1], tileW - 12), x + 6, ty + 31, TEXT);
        }
        return y + 2 * (TILE_H + GAP);
    }

    private int drawPrayer(Graphics2D g, int y) {
        int xp = client.getSkillExperience(Skill.PRAYER);
        int level = Experience.getLevelForXp(xp);
        int gained = stats.startXp() < 0 ? 0 : level - Experience.getLevelForXp(stats.startXp());

        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "PRAYER", PAD, y + 13, MUTED);
        textRight(g, "4x bury xp per worship", WIDTH - PAD, y + 13, MUTED);
        y += SECTION_H;

        g.setFont(FontManager.getRunescapeFont());
        text(g, "Level " + level + (gained > 0 ? "  (+" + gained + ")" : ""), PAD, y + 11, TEXT);
        y += LINE_H;

        int barW = WIDTH - 2 * PAD;
        g.setColor(BAR_BG);
        g.fill(new RoundRectangle2D.Float(PAD, y + 2, barW, 6, 6, 6));
        double progress = 1;
        if (level < Experience.MAX_REAL_LEVEL) {
            int from = Experience.getXpForLevel(level);
            int to = Experience.getXpForLevel(level + 1);
            progress = Math.min(1, Math.max(0, (xp - from) / (double) (to - from)));
        }
        int fill = (int) Math.round(barW * progress);
        if (fill > 0) {
            g.setPaint(new GradientPaint(PAD, 0, ACCENT_2, PAD + barW, 0, ACCENT));
            g.fill(new RoundRectangle2D.Float(PAD, y + 2, Math.max(fill, 6), 6, 6, 6));
        }
        y += BAR_H;

        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "Next level", PAD, y + 11, MUTED);
        textRight(g, eta(level + 1, xp, stats.perHour(xpGained())), WIDTH - PAD, y + 11, TEXT);
        return y + LINE_H;
    }

    private void drawRun(Graphics2D g, int y) {
        g.setFont(FontManager.getRunescapeSmallFont());
        g.setColor(CARD_BORDER);
        g.drawLine(PAD, y - 2, WIDTH - PAD, y - 2);
        text(g, "SUPPLIES", PAD, y + 13, MUTED);
        y += SECTION_H;

        row(g, y, "Bones / empty pots", pair(stats.bones(), stats.pots()), TEXT);
        y += LINE_H;
        row(g, y, "Bonemeal / slime", pair(stats.bonemeal(), stats.slime()), ACCENT);
        y += LINE_H;
        row(g, y, "Empty buckets", count(stats.buckets()), TEXT);
        y += LINE_H;
        row(g, y, "Unclaimed tokens", count(stats.unclaimed()), stats.unclaimed() > 0 ? GOOD : TEXT);
    }

    private static String count(int n) {
        return n < 0 ? "-" : formatNumber(n);
    }

    private static String pair(int a, int b) {
        return count(a) + " / " + count(b);
    }

    // --- Helpers ---

    private void row(Graphics2D g, int y, String label, String value, Color valueColor) {
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, label, PAD, y + 11, MUTED);
        textRight(g, ellipsize(g, value, WIDTH - 2 * PAD - g.getFontMetrics().stringWidth(label) - 10), WIDTH - PAD, y + 11, valueColor);
    }

    private long xpGained() {
        int start = stats.startXp();
        return start < 0 ? 0 : Math.max(0, client.getSkillExperience(Skill.PRAYER) - start);
    }

    private String currentState() {
        StateSnapshot<EctofuntusScript.State> snapshot = script.getSnapshot();
        return snapshot != null && snapshot.currentState() != null ? snapshot.currentState().name() : "";
    }

    /** Time to reach a level at the current xp rate, e.g. "~1h 12m". */
    private static String eta(int targetLevel, int xp, long xpPerHour) {
        if (targetLevel > Experience.MAX_REAL_LEVEL) return "max";
        int target = Experience.getXpForLevel(targetLevel);
        if (xpPerHour <= 0) return "…";
        long minutes = Math.max(0, target - xp) * 60L / xpPerHour;
        return minutes >= 60 ? "~" + minutes / 60 + "h " + minutes % 60 + "m" : "~" + Math.max(1, minutes) + "m";
    }

    private static void text(Graphics2D g, String s, int x, int y, Color color) {
        g.setColor(SHADOW);
        g.drawString(s, x + 1, y + 1);
        g.setColor(color);
        g.drawString(s, x, y);
    }

    private static void textRight(Graphics2D g, String s, int rightX, int y, Color color) {
        text(g, s, rightX - g.getFontMetrics().stringWidth(s), y, color);
    }

    private static String ellipsize(Graphics2D g, String s, int maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxWidth) return s;
        String dots = "…";
        int end = s.length();
        while (end > 0 && fm.stringWidth(s.substring(0, end) + dots) > maxWidth) end--;
        return s.substring(0, end) + dots;
    }

    private static String pretty(String state) {
        String s = state.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String formatDuration(Duration d) {
        long secs = d.getSeconds();
        return String.format("%d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
    }

    private static String formatNumber(long n) {
        if (n >= 1_000_000) return String.format("%.1fm", n / 1_000_000.0);
        if (n >= 10_000) return String.format("%.1fk", n / 1_000.0);
        return String.format("%,d", n);
    }
}
