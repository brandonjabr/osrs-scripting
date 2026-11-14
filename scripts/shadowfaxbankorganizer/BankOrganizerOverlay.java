package net.runelite.client.plugins.microbot.shadowfaxbankorganizer;

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
import java.util.Map;

/** Progress card: header with phase and status, session tiles, and one progress row per planned tab. */
public class BankOrganizerOverlay extends Overlay {

    private static final int WIDTH = 250;
    private static final int PAD = 12;
    private static final int GAP = 6;
    private static final int HEADER_H = 40;
    private static final int TILE_H = 38;
    private static final int SECTION_H = 20;
    private static final int ROW_H = 26;

    private static final Color CARD_BG = new Color(16, 18, 24, 225);
    private static final Color CARD_BORDER = new Color(255, 255, 255, 30);
    private static final Color TILE_BG = new Color(255, 255, 255, 14);
    private static final Color BAR_BG = new Color(255, 255, 255, 22);
    private static final Color TEXT = new Color(236, 238, 242);
    private static final Color MUTED = new Color(150, 156, 170);
    private static final Color ACCENT = new Color(120, 200, 190);
    private static final Color ACCENT_2 = new Color(110, 150, 240);
    private static final Color DONE = new Color(120, 200, 110);
    private static final Color SHADOW = new Color(0, 0, 0, 160);

    private final BankOrganizerConfig config;
    private final BankOrganizerStats stats;
    private final BankOrganizerScript script;

    @Inject
    BankOrganizerOverlay(BankOrganizerPlugin plugin, BankOrganizerConfig config, BankOrganizerStats stats,
                         BankOrganizerScript script) {
        super(plugin);
        this.config = config;
        this.stats = stats;
        this.script = script;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setDragTargetable(true);
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (!config.showOverlay()) return null;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Map<BankCategory, Integer> planned = stats.planned();
        int height = PAD + HEADER_H + TILE_H + GAP + SECTION_H + Math.max(1, planned.size()) * ROW_H + PAD;

        drawCard(g, height);
        int y = PAD;
        y = drawHeader(g, y);
        y = drawTiles(g, y);
        drawTabs(g, y, planned);
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
        String state = currentState();
        g.setColor("BUILD".equals(state) || "VERIFY".equals(state) ? DONE : ACCENT_2);
        g.fillOval(PAD, y + 5, 8, 8);
        g.setFont(FontManager.getRunescapeBoldFont());
        text(g, "Shadowfax Bank Organizer", PAD + 14, y + 14, TEXT);
        g.setFont(FontManager.getRunescapeSmallFont());
        textRight(g, "v" + BankOrganizerPlugin.VERSION, WIDTH - PAD, y + 13, MUTED);
        String status = Microbot.status != null ? Microbot.status : "";
        String line = state.isEmpty() ? status : pretty(state) + "  ·  " + status;
        text(g, ellipsize(g, line, WIDTH - 2 * PAD), PAD, y + 32, MUTED);
        return y + HEADER_H;
    }

    private int drawTiles(Graphics2D g, int y) {
        int cols = 3;
        int tileW = (WIDTH - 2 * PAD - (cols - 1) * GAP) / cols;
        String[][] tiles = {
                {"RUNTIME", formatDuration(stats.runtime())},
                {"SORTED", stats.done() + " / " + stats.totalItems()},
                {"MOVED", String.valueOf(stats.moved())},
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
        return y + TILE_H + GAP;
    }

    private void drawTabs(Graphics2D g, int y, Map<BankCategory, Integer> planned) {
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "TABS", PAD, y + 13, MUTED);
        if (stats.skipped() > 0) textRight(g, stats.skipped() + " skipped", WIDTH - PAD, y + 13, MUTED);
        y += SECTION_H;

        if (planned.isEmpty()) {
            text(g, "Listing the bank, then planning…", PAD, y + 12, MUTED);
            return;
        }
        int tab = 1;
        int barW = WIDTH - 2 * PAD;
        for (Map.Entry<BankCategory, Integer> e : planned.entrySet()) {
            int placed = stats.placed(e.getKey());
            boolean complete = placed >= e.getValue();
            g.setFont(FontManager.getRunescapeFont());
            text(g, tab + "  " + e.getKey().label, PAD, y + 11, e.getKey() == stats.current() && !complete ? TEXT : MUTED);
            g.setFont(FontManager.getRunescapeSmallFont());
            textRight(g, placed + " / " + e.getValue(), WIDTH - PAD, y + 11, complete ? DONE : MUTED);

            g.setColor(BAR_BG);
            g.fill(new RoundRectangle2D.Float(PAD, y + 16, barW, 5, 5, 5));
            int fill = e.getValue() == 0 ? 0 : (int) Math.round(barW * Math.min(1.0, placed / (double) e.getValue()));
            if (fill > 0) {
                g.setPaint(new GradientPaint(PAD, 0, ACCENT_2, PAD + barW, 0, complete ? DONE : ACCENT));
                g.fill(new RoundRectangle2D.Float(PAD, y + 16, Math.max(fill, 5), 5, 5, 5));
            }
            y += ROW_H;
            tab++;
        }
    }

    private String currentState() {
        StateSnapshot<BankOrganizerScript.State> snapshot = script.getSnapshot();
        return snapshot != null && snapshot.currentState() != null ? snapshot.currentState().name() : "";
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
}
