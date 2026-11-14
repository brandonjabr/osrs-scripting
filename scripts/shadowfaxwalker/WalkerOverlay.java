package net.runelite.client.plugins.microbot.shadowfaxwalker;

import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.Keybind;
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
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Trip card drawn with Graphics2D: header with live state, the destination with its category, a progress bar by
 * distance covered, and elapsed / ETA / run energy tiles.
 */
public class WalkerOverlay extends Overlay {

    private static final int WIDTH = 250;
    private static final int PAD = 12;
    private static final int GAP = 6;
    private static final int HEADER_H = 40;
    private static final int TILE_H = 38;
    private static final int SECTION_H = 20;
    private static final int LINE_H = 15;
    private static final int NAME_H = 18;
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
    private static final Color BAD = new Color(230, 90, 90);
    private static final Color SHADOW = new Color(0, 0, 0, 160);

    private final Client client;
    private final WalkerConfig config;
    private final WalkerScript script;

    @Inject
    WalkerOverlay(WalkerPlugin plugin, Client client, WalkerConfig config, WalkerScript script) {
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

        Destination d = script.getDestination();
        int height = PAD + HEADER_H
                + (d == null ? SECTION_H + 2 * LINE_H
                : SECTION_H + NAME_H + LINE_H + GAP + BAR_H + LINE_H + GAP + TILE_H)
                + GAP + LINE_H
                + PAD;

        drawCard(g, height);
        int y = PAD;
        y = drawHeader(g, y);
        y = d == null ? drawWaiting(g, y) : drawTrip(g, y, d);
        drawHint(g, y + GAP);
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
        WalkerScript.State state = script.getCurrentState();
        g.setColor(stateColor(state));
        g.fillOval(PAD, y + 5, 8, 8);

        g.setFont(FontManager.getRunescapeBoldFont());
        text(g, "Shadowfax Walker", PAD + 14, y + 14, TEXT);
        g.setFont(FontManager.getRunescapeSmallFont());
        textRight(g, "v" + WalkerPlugin.VERSION, WIDTH - PAD, y + 13, MUTED);

        String status = Microbot.status != null ? Microbot.status : "";
        String line = state == null ? status : pretty(state.name()) + "  ·  " + status;
        text(g, ellipsize(g, line, WIDTH - 2 * PAD), PAD, y + 32, MUTED);
        return y + HEADER_H;
    }

    private int drawWaiting(Graphics2D g, int y) {
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "DESTINATION", PAD, y + 13, MUTED);
        y += SECTION_H;
        g.setFont(FontManager.getRunescapeFont());
        text(g, "Where to? Pick a place in the window", PAD, y + 11, TEXT);
        y += LINE_H;
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "Type to search, Enter to walk", PAD, y + 11, MUTED);
        return y + LINE_H;
    }

    private int drawTrip(Graphics2D g, int y, Destination d) {
        Color color = d.getCategory().getColor();

        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "DESTINATION", PAD, y + 13, MUTED);
        // Category pill on the right of the section label.
        String cat = d.getCategory().getLabel();
        FontMetrics fm = g.getFontMetrics();
        int pw = fm.stringWidth(cat) + 18;
        int px = WIDTH - PAD - pw;
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 45));
        g.fill(new RoundRectangle2D.Float(px, y + 2, pw, 14, 14, 14));
        g.setColor(color);
        g.fill(new Ellipse2D.Float(px + 6, y + 7, 5, 5));
        text(g, cat, px + 14, y + 13, color);
        y += SECTION_H;

        g.setFont(FontManager.getRunescapeBoldFont());
        text(g, ellipsize(g, d.getName(), WIDTH - 2 * PAD), PAD, y + 13, TEXT);
        y += NAME_H;
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, d.coordinates(), PAD, y + 11, MUTED);

        WorldPoint here = script.playerLocation();
        int left = here == null ? -1 : DestinationPicker.distanceBetween(here, d.getPoint());
        String leftText = left < 0 ? "-" : (left <= Math.max(1, config.arrivalDistance()) ? "here" : left + " tiles to go");
        textRight(g, leftText, WIDTH - PAD, y + 11, script.getArrivedAt() > 0 ? GOOD : TEXT);
        y += LINE_H + GAP;

        // Progress by straight-line distance covered since the walk started.
        int total = Math.max(1, script.getStartDistance());
        double progress = script.getArrivedAt() > 0 ? 1 : left < 0 ? 0 : Math.min(1, Math.max(0, 1 - left / (double) total));
        int barW = WIDTH - 2 * PAD;
        g.setColor(BAR_BG);
        g.fill(new RoundRectangle2D.Float(PAD, y + 2, barW, 6, 6, 6));
        int fill = (int) Math.round(barW * progress);
        if (fill > 0) {
            if (script.getArrivedAt() > 0) g.setColor(GOOD);
            else g.setPaint(new GradientPaint(PAD, 0, ACCENT_2, PAD + barW, 0, ACCENT));
            g.fill(new RoundRectangle2D.Float(PAD, y + 2, Math.max(fill, 6), 6, 6, 6));
        }
        y += BAR_H;
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, Math.round(progress * 100) + "% of the way", PAD, y + 11, MUTED);
        textRight(g, script.getTrips() + (script.getTrips() == 1 ? " trip" : " trips") + " this session", WIDTH - PAD, y + 11, MUTED);
        y += LINE_H + GAP;

        int cols = 3;
        int tileW = (WIDTH - 2 * PAD - (cols - 1) * GAP) / cols;
        long end = script.getArrivedAt() > 0 ? script.getArrivedAt() : System.currentTimeMillis();
        long elapsedMs = script.getWalkStartedAt() > 0 ? end - script.getWalkStartedAt() : 0;
        String[][] tiles = {
                {script.getArrivedAt() > 0 ? "TOOK" : "ELAPSED", formatDuration(elapsedMs)},
                {"ETA", script.getArrivedAt() > 0 ? "done" : eta(total, left, elapsedMs)},
                {"RUN", client.getEnergy() / 100 + "%"},
        };
        for (int i = 0; i < tiles.length; i++) {
            int x = PAD + i * (tileW + GAP);
            g.setColor(TILE_BG);
            g.fill(new RoundRectangle2D.Float(x, y, tileW, TILE_H, 8, 8));
            g.setFont(FontManager.getRunescapeSmallFont());
            text(g, tiles[i][0], x + 6, y + 13, MUTED);
            g.setFont(FontManager.getRunescapeBoldFont());
            text(g, ellipsize(g, tiles[i][1], tileW - 12), x + 6, y + 31, TEXT);
        }
        return y + TILE_H;
    }

    private void drawHint(Graphics2D g, int y) {
        g.setFont(FontManager.getRunescapeSmallFont());
        g.setColor(CARD_BORDER);
        g.drawLine(PAD, y - 2, WIDTH - PAD, y - 2);
        Keybind key = config.pickerHotkey();
        String hint = key == null || Keybind.NOT_SET.equals(key)
                ? "Set a hotkey in the config to change course"
                : key + "  choose a new destination";
        text(g, ellipsize(g, hint, WIDTH - 2 * PAD), PAD, y + 11, MUTED);
    }

    // --- Helpers ---

    private static Color stateColor(WalkerScript.State state) {
        if (state == null) return MUTED;
        switch (state) {
            case WALK:
                return ACCENT;
            case ARRIVED:
                return GOOD;
            case FINISH:
                return BAD;
            default:
                return ACCENT_2;
        }
    }

    /** Remaining time at the average pace so far, e.g. "~2m 10s". */
    private static String eta(int total, int left, long elapsedMs) {
        int covered = total - left;
        if (left < 0 || covered <= 2 || elapsedMs < 3_000) return "…";
        long ms = (long) (left * (elapsedMs / (double) covered));
        return "~" + formatShort(ms);
    }

    private static String formatShort(long ms) {
        long secs = ms / 1000;
        if (secs >= 3600) return secs / 3600 + "h " + (secs % 3600) / 60 + "m";
        if (secs >= 60) return secs / 60 + "m " + secs % 60 + "s";
        return Math.max(1, secs) + "s";
    }

    private static String formatDuration(long ms) {
        long secs = ms / 1000;
        return secs >= 3600
                ? String.format("%d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60)
                : String.format("%d:%02d", secs / 60, secs % 60);
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
}
