package net.runelite.client.plugins.microbot.shadowfaxquesting;

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
import java.util.ArrayList;
import java.util.List;

import static net.runelite.client.plugins.microbot.shadowfaxquesting.QuestingStats.Status;

/**
 * Progress card drawn with Graphics2D: header with phase and live status, session tiles, the current quest with its
 * step text, and one row per selected quest (skipped and waiting quests show why underneath).
 */
public class QuestingOverlay extends Overlay {

    private static final int WIDTH = 260;
    private static final int PAD = 12;
    private static final int GAP = 6;
    private static final int HEADER_H = 40;
    private static final int TILE_H = 38;
    private static final int SECTION_H = 20;
    private static final int LINE_H = 14;
    private static final int ROW_H = 15;
    private static final int MAX_STEP_LINES = 3;

    private static final Color CARD_BG = new Color(16, 18, 24, 225);
    private static final Color CARD_BORDER = new Color(255, 255, 255, 30);
    private static final Color TILE_BG = new Color(255, 255, 255, 14);
    private static final Color TEXT = new Color(236, 238, 242);
    private static final Color MUTED = new Color(150, 156, 170);
    private static final Color ACCENT = new Color(110, 190, 240);
    private static final Color ACCENT_2 = new Color(170, 140, 245);
    private static final Color DONE = new Color(120, 200, 110);
    private static final Color RUNNING = new Color(240, 190, 90);
    private static final Color SKIPPED = new Color(240, 110, 90);
    private static final Color SHADOW = new Color(0, 0, 0, 160);

    private final QuestingConfig config;
    private final QuestingStats stats;

    @Inject
    QuestingOverlay(QuestingPlugin plugin, QuestingConfig config, QuestingStats stats) {
        super(plugin);
        this.config = config;
        this.stats = stats;
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

        g.setFont(FontManager.getRunescapeSmallFont());
        List<String> stepLines = wrap(g, stats.currentStep(), WIDTH - 2 * PAD, MAX_STEP_LINES);
        List<QuestingStats.Row> rows = stats.rows();
        int detailLines = (int) rows.stream().filter(QuestingOverlay::showsDetail).count();

        boolean questing = !stats.currentQuest().isEmpty();
        int height = PAD + HEADER_H
                + 2 * (TILE_H + GAP)
                + (questing ? SECTION_H + LINE_H + stepLines.size() * LINE_H + GAP : 0)
                + SECTION_H + Math.max(1, rows.size()) * ROW_H + detailLines * LINE_H
                + PAD;

        drawCard(g, height);
        int y = PAD;
        y = drawHeader(g, y);
        y = drawTiles(g, y);
        if (questing) y = drawCurrent(g, y, stepLines) + GAP;
        drawQuests(g, y, rows);

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
        String phase = stats.phase();
        g.setColor("RUN".equals(phase) ? DONE : RUNNING);
        g.fillOval(PAD, y + 5, 8, 8);

        g.setFont(FontManager.getRunescapeBoldFont());
        text(g, "Shadowfax Questing", PAD + 14, y + 14, TEXT);
        g.setFont(FontManager.getRunescapeSmallFont());
        textRight(g, "v" + QuestingPlugin.VERSION, WIDTH - PAD, y + 13, MUTED);

        String status = Microbot.status != null ? Microbot.status : "";
        String line = phase.isEmpty() ? status : pretty(phase) + "  ·  " + status;
        text(g, ellipsize(g, line, WIDTH - 2 * PAD), PAD, y + 32, MUTED);
        return y + HEADER_H;
    }

    private int drawTiles(Graphics2D g, int y) {
        int cols = 3;
        int tileW = (WIDTH - 2 * PAD - (cols - 1) * GAP) / cols;
        long done = stats.count(Status.DONE);
        long total = stats.rows().size();
        long finished = stats.count(Status.DONE, Status.ALREADY_DONE);
        String[][] tiles = {
                {"RUNTIME", formatDuration(stats.runtime())},
                {"COMPLETED", done + ""},
                {"QUEST POINTS", "+" + stats.questPointsGained()},
                {"DONE / TOTAL", finished + " / " + total},
                {"SKIPPED", stats.count(Status.SKIPPED) + ""},
                {"GP SPENT", formatNumber(stats.gpSpent())},
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

    private int drawCurrent(Graphics2D g, int y, List<String> stepLines) {
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "NOW", PAD, y + 13, MUTED);
        y += SECTION_H;
        g.setFont(FontManager.getRunescapeBoldFont());
        text(g, ellipsize(g, stats.currentQuest(), WIDTH - 2 * PAD), PAD, y + 11, ACCENT);
        y += LINE_H;
        g.setFont(FontManager.getRunescapeSmallFont());
        for (String line : stepLines) {
            text(g, line, PAD, y + 11, TEXT);
            y += LINE_H;
        }
        return y;
    }

    private void drawQuests(Graphics2D g, int y, List<QuestingStats.Row> rows) {
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "QUESTS", PAD, y + 13, MUTED);
        y += SECTION_H;

        if (rows.isEmpty()) {
            text(g, "No quests selected", PAD, y + 11, MUTED);
            return;
        }
        for (QuestingStats.Row row : rows) {
            Color color = statusColor(row.status);
            g.setColor(color);
            g.fillOval(PAD, y + 4, 6, 6);

            g.setFont(FontManager.getRunescapeFont());
            String right = row.status == Status.DONE && row.took != null
                    ? "done  " + formatDuration(row.took)
                    : row.status.label;
            g.setFont(FontManager.getRunescapeSmallFont());
            int rightW = g.getFontMetrics().stringWidth(right);
            textRight(g, right, WIDTH - PAD, y + 11, color);
            g.setFont(FontManager.getRunescapeFont());
            text(g, ellipsize(g, row.quest, WIDTH - 2 * PAD - 14 - rightW - 6), PAD + 12, y + 11,
                    row.status == Status.RUNNING ? TEXT : MUTED);
            y += ROW_H;

            if (showsDetail(row)) {
                g.setFont(FontManager.getRunescapeSmallFont());
                text(g, ellipsize(g, row.detail, WIDTH - 2 * PAD - 12), PAD + 12, y + 10, MUTED);
                y += LINE_H;
            }
        }
    }

    // --- Helpers ---

    private static boolean showsDetail(QuestingStats.Row row) {
        return (row.status == Status.SKIPPED || row.status == Status.WAITING || row.status == Status.STUCK) && row.detail != null && !row.detail.isEmpty();
    }

    private static Color statusColor(Status status) {
        switch (status) {
            case DONE:
            case ALREADY_DONE:
                return DONE;
            case RUNNING:
                return RUNNING;
            case SKIPPED:
            case STUCK:
                return SKIPPED;
            default:
                return MUTED;
        }
    }

    /** Word-wraps text to at most {@code maxLines} lines, ellipsizing the last. */
    private static List<String> wrap(Graphics2D g, String text, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(candidate) <= width) {
                line = new StringBuilder(candidate);
                continue;
            }
            if (line.length() > 0) lines.add(line.toString());
            line = new StringBuilder(word);
            if (lines.size() == maxLines) break;
        }
        if (lines.size() < maxLines && line.length() > 0) lines.add(line.toString());
        if (lines.size() > maxLines) lines = lines.subList(0, maxLines);
        if (!lines.isEmpty() && lines.size() == maxLines) {
            int last = lines.size() - 1;
            lines.set(last, ellipsize(g, lines.get(last), width));
        }
        return lines;
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
