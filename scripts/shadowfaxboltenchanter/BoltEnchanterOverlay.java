package net.runelite.client.plugins.microbot.shadowfaxboltenchanter;

import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
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
 * Stats card drawn straight with Graphics2D: header with status, a grid of session tiles, and the bolts being
 * enchanted with progress to the next Magic level.
 */
public class BoltEnchanterOverlay extends Overlay {

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
    private static final Color ACCENT = new Color(110, 140, 250);
    private static final Color ACCENT_2 = new Color(190, 120, 240);
    private static final Color GOOD = new Color(120, 200, 110);
    private static final Color SHADOW = new Color(0, 0, 0, 160);

    private final Client client;
    private final BoltEnchanterConfig config;
    private final BoltEnchanterScript script;

    @Inject
    BoltEnchanterOverlay(BoltEnchanterPlugin plugin, Client client, BoltEnchanterConfig config,
                         BoltEnchanterScript script) {
        super(plugin);
        this.client = client;
        this.config = config;
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

        int height = PAD + HEADER_H + 2 * (TILE_H + GAP) + SECTION_H + 2 * LINE_H + BAR_H + PAD;
        drawCard(g, height);
        int y = drawHeader(g, PAD);
        y = drawTiles(g, y);
        drawBolts(g, y);
        return new Dimension(WIDTH, height);
    }

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
        g.setColor(script.current() != null ? GOOD : ACCENT_2);
        g.fillOval(PAD, y + 5, 8, 8);

        g.setFont(FontManager.getRunescapeBoldFont());
        text(g, "Shadowfax Bolt Enchanter", PAD + 14, y + 14, TEXT);

        g.setFont(FontManager.getRunescapeSmallFont());
        textRight(g, "v" + BoltEnchanterPlugin.VERSION, WIDTH - PAD, y + 13, MUTED);

        String status = Microbot.status != null ? Microbot.status : "";
        text(g, ellipsize(g, status, WIDTH - 2 * PAD), PAD, y + 32, MUTED);
        return y + HEADER_H;
    }

    private int drawTiles(Graphics2D g, int y) {
        int cols = 3;
        int tileW = (WIDTH - 2 * PAD - (cols - 1) * GAP) / cols;
        long xp = xpGained();
        int enchanted = script.enchanted();
        int level = client.getRealSkillLevel(Skill.MAGIC);
        int levelsGained = script.startXp() < 0 ? 0 : level - Experience.getLevelForXp(script.startXp());

        String[][] tiles = {
                {"RUNTIME", formatDuration(script.runtime())},
                {"ENCHANTED", formatNumber(enchanted)},
                {"BOLTS / HR", formatNumber(perHour(enchanted))},
                {"MAGIC", level + (levelsGained > 0 ? "  (+" + levelsGained + ")" : "")},
                {"XP GAINED", formatNumber(xp)},
                {"XP / HR", formatNumber(perHour(xp))},
        };
        for (int i = 0; i < tiles.length; i++) {
            int x = PAD + (i % cols) * (tileW + GAP);
            int ty = y + (i / cols) * (TILE_H + GAP);
            drawTile(g, x, ty, tileW, tiles[i][0], tiles[i][1]);
        }
        return y + (tiles.length / cols) * (TILE_H + GAP);
    }

    private void drawTile(Graphics2D g, int x, int y, int w, String label, String value) {
        g.setColor(TILE_BG);
        g.fill(new RoundRectangle2D.Float(x, y, w, TILE_H, 8, 8));
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, ellipsize(g, label, w - 12), x + 6, y + 13, MUTED);
        g.setFont(FontManager.getRunescapeBoldFont());
        text(g, ellipsize(g, value, w - 12), x + 6, y + 31, TEXT);
    }

    private void drawBolts(Graphics2D g, int y) {
        BoltType bolts = script.current();
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "BOLTS", PAD, y + 13, MUTED);
        y += SECTION_H;

        g.setFont(FontManager.getRunescapeFont());
        text(g, bolts != null ? bolts + " bolts" : "-", PAD, y + 11, ACCENT_2);
        if (bolts != null) {
            textRight(g, formatNumber(script.boltsLeft()) + " left", WIDTH - PAD, y + 11, TEXT);
        }
        y += LINE_H;

        int xp = client.getSkillExperience(Skill.MAGIC);
        int level = Experience.getLevelForXp(xp);
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
            g.setPaint(new GradientPaint(PAD, 0, ACCENT, PAD + barW, 0, ACCENT_2));
            g.fill(new RoundRectangle2D.Float(PAD, y + 2, Math.max(fill, 6), 6, 6, 6));
        }
        y += BAR_H;

        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "Next level " + Math.min(level + 1, Experience.MAX_REAL_LEVEL), PAD, y + 11, MUTED);
        if (bolts != null && level < Experience.MAX_REAL_LEVEL) {
            // Each cast enchants 10 bolts.
            long casts = (long) Math.ceil((Experience.getXpForLevel(level + 1) - xp) / bolts.xp);
            textRight(g, formatNumber(casts * 10) + " bolts to go", WIDTH - PAD, y + 11, TEXT);
        }
    }

    // --- Data & formatting ---

    private long xpGained() {
        int start = script.startXp();
        return start < 0 ? 0 : Math.max(0, client.getSkillExperience(Skill.MAGIC) - start);
    }

    private long perHour(long amount) {
        long ms = script.runtime().toMillis();
        return ms < 5_000 ? 0 : amount * 3_600_000L / ms;
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
