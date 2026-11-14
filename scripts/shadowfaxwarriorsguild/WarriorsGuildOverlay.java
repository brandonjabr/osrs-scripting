package net.runelite.client.plugins.microbot.shadowfaxwarriorsguild;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Stats card drawn straight with Graphics2D: header with live state, session tiles, the defender ladder (owned,
 * next drop and where it drops, what Kamfreena has seen, kills since the last one) and per-skill xp rows.
 */
public class WarriorsGuildOverlay extends Overlay {

    private static final int WIDTH = 250;
    private static final int PAD = 12;
    private static final int GAP = 6;
    private static final int HEADER_H = 40;
    private static final int TILE_H = 38;
    private static final int SECTION_H = 20;
    private static final int LADDER_H = 70;
    private static final int SKILL_ROW_H = 30;
    private static final int FOOTER_LINE_H = 15;

    private static final Color CARD_BG = new Color(16, 18, 24, 225);
    private static final Color CARD_BORDER = new Color(255, 255, 255, 30);
    private static final Color TILE_BG = new Color(255, 255, 255, 14);
    private static final Color BAR_BG = new Color(255, 255, 255, 22);
    private static final Color TEXT = new Color(236, 238, 242);
    private static final Color MUTED = new Color(150, 156, 170);
    private static final Color ACCENT = new Color(230, 170, 70);
    private static final Color ACCENT_2 = new Color(210, 80, 70);
    private static final Color SHADOW = new Color(0, 0, 0, 160);

    private final Client client;
    private final WarriorsGuildConfig config;
    private final WarriorsGuildStats stats;
    private final WarriorsGuildScript script;

    @Inject
    WarriorsGuildOverlay(WarriorsGuildPlugin plugin, Client client, WarriorsGuildConfig config,
                         WarriorsGuildStats stats, WarriorsGuildScript script) {
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

        List<SkillLine> skills = skillLines();
        int tileRows = 2;
        int height = PAD + HEADER_H
                + tileRows * (TILE_H + GAP)
                + SECTION_H + LADDER_H + GAP
                + SECTION_H + Math.max(1, skills.size()) * SKILL_ROW_H
                + GAP + FOOTER_LINE_H + PAD;

        drawCard(g, height);
        int y = PAD;
        y = drawHeader(g, y);
        y = drawTiles(g, y);
        y = drawLadder(g, y) + GAP;
        y = drawSkills(g, y, skills);
        drawFooter(g, y + GAP);

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
        boolean fighting = "CYCLOPS".equals(state) || "TOKENS".equals(state);

        g.setColor(fighting ? ACCENT : ACCENT_2);
        g.fillOval(PAD, y + 5, 8, 8);

        g.setFont(FontManager.getRunescapeBoldFont());
        text(g, "Shadowfax Warrior's Guild", PAD + 14, y + 14, TEXT);

        g.setFont(FontManager.getRunescapeSmallFont());
        textRight(g, "v" + WarriorsGuildPlugin.VERSION, WIDTH - PAD, y + 13, MUTED);

        String status = Microbot.status != null ? Microbot.status : "";
        String line = state.isEmpty() ? status : prettyState(state) + "  ·  " + status;
        text(g, ellipsize(g, line, WIDTH - 2 * PAD), PAD, y + 32, MUTED);

        return y + HEADER_H;
    }

    private int drawTiles(Graphics2D g, int y) {
        int cols = 3;
        int tileW = (WIDTH - 2 * PAD - (cols - 1) * GAP) / cols;

        String[][] tiles = {
                {"RUNTIME", formatDuration(stats.runtime())},
                {"TOKENS", formatNumber(script.tokens)},
                {"TOKENS / HR", formatNumber(stats.perHour(stats.tokensEarned()))},
                {"ARMOUR KILLS", formatNumber(stats.armourKills())},
                {"CYCLOPS KILLS", formatNumber(stats.cyclopsKills())},
                {"DEFENDERS", formatNumber(stats.defenders().size())},
        };

        for (int i = 0; i < tiles.length; i++) {
            int col = i % cols;
            int row = i / cols;
            int x = PAD + col * (tileW + GAP);
            int ty = y + row * (TILE_H + GAP);
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

    /** One box per tier: filled up to the best owned, the next drop outlined, then where it drops and the odds. */
    private int drawLadder(Graphics2D g, int y) {
        Defender best = script.bestOwned;
        Defender next = Defender.nextAfter(best);
        boolean done = best != null && best.ordinal() >= config.stopAt().ordinal();

        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "DEFENDERS", PAD, y + 13, MUTED);
        textRight(g, done ? "Done" : "Goal: " + config.stopAt().metal, WIDTH - PAD, y + 13, MUTED);
        y += SECTION_H;

        Defender[] tiers = Defender.values();
        int boxGap = 4;
        int boxW = (WIDTH - 2 * PAD - (tiers.length - 1) * boxGap) / tiers.length;
        for (int i = 0; i < tiers.length; i++) {
            int x = PAD + i * (boxW + boxGap);
            Defender d = tiers[i];
            boolean owned = best != null && d.ordinal() <= best.ordinal();
            RoundRectangle2D box = new RoundRectangle2D.Float(x, y, boxW, 18, 5, 5);
            g.setColor(owned ? tierColor(d) : TILE_BG);
            g.fill(box);
            if (!done && d == next) {
                g.setColor(ACCENT);
                g.setStroke(new BasicStroke(1.5f));
                g.draw(box);
            }
            g.setFont(FontManager.getRunescapeSmallFont());
            String initial = d.metal.substring(0, d == Defender.MITHRIL ? 4 : Math.min(3, d.metal.length()));
            FontMetrics fm = g.getFontMetrics();
            text(g, initial, x + (boxW - fm.stringWidth(initial)) / 2, y + 13, owned ? TEXT : MUTED);
        }
        y += 18 + 6;

        g.setFont(FontManager.getRunescapeFont());
        String bestText = "Best  " + (best != null ? best.metal : "none");
        text(g, bestText, PAD, y + 12, best != null ? tierColor(best).brighter() : MUTED);
        if (!done) {
            textRight(g, "Next  " + next.metal + (next.dropsInBasement() ? " (basement)" : " (top floor)"),
                    WIDTH - PAD, y + 12, TEXT);
        }
        y += 16;

        g.setFont(FontManager.getRunescapeSmallFont());
        Defender shown = script.lastShown;
        int odds = next == Defender.DRAGON ? 100 : 50;
        text(g, "Kamfreena saw  " + (shown != null ? shown.metal : "—"), PAD, y + 11, MUTED);
        textRight(g, stats.killsSinceDefender() + " kills since last  ·  1/" + odds, WIDTH - PAD, y + 11, MUTED);
        return y + 16;
    }

    private int drawSkills(Graphics2D g, int y, List<SkillLine> skills) {
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "EXPERIENCE", PAD, y + 13, MUTED);
        textRight(g, formatNumber(totalXpGained()) + " xp gained", WIDTH - PAD, y + 13, MUTED);
        y += SECTION_H;

        if (skills.isEmpty()) {
            text(g, "Waiting for the first xp drop…", PAD, y + 16, MUTED);
            return y + SKILL_ROW_H;
        }

        for (SkillLine s : skills) {
            g.setFont(FontManager.getRunescapeFont());
            String left = s.skill.getName() + "  " + s.level + (s.levelsGained > 0 ? "  (+" + s.levelsGained + ")" : "");
            text(g, left, PAD, y + 12, TEXT);
            g.setFont(FontManager.getRunescapeSmallFont());
            textRight(g, formatNumber(stats.perHour(s.xpGained)) + "/hr", WIDTH - PAD, y + 12, MUTED);

            int barY = y + 17;
            int barW = WIDTH - 2 * PAD;
            g.setColor(BAR_BG);
            g.fill(new RoundRectangle2D.Float(PAD, barY, barW, 6, 6, 6));
            int fill = (int) Math.round(barW * s.progress);
            if (fill > 0) {
                Color c = skillColor(s.skill);
                g.setPaint(new GradientPaint(PAD, 0, c.darker(), PAD + barW, 0, c));
                g.fill(new RoundRectangle2D.Float(PAD, barY, Math.max(fill, 6), 6, 6, 6));
            }
            y += SKILL_ROW_H;
        }
        return y;
    }

    private void drawFooter(Graphics2D g, int y) {
        g.setFont(FontManager.getRunescapeSmallFont());
        g.setColor(CARD_BORDER);
        g.drawLine(PAD, y - 2, WIDTH - PAD, y - 2);

        text(g, "Tokens spent  " + formatNumber(stats.tokensSpent()) + "  ·  Banks  " + stats.bankTrips()
                + "  ·  Recov.  " + stats.recoveries(), PAD, y + 11, MUTED);
    }

    // --- Data ---

    private static final class SkillLine {
        Skill skill;
        int level;
        int levelsGained;
        long xpGained;
        double progress;
    }

    /** Skills that gained xp this session, in tracked order. Runs on the client thread, so client reads are safe. */
    private List<SkillLine> skillLines() {
        List<SkillLine> lines = new ArrayList<>();
        for (Skill skill : WarriorsGuildStats.TRACKED_SKILLS) {
            int start = stats.startXp(skill);
            if (start < 0) continue;
            int xp = client.getSkillExperience(skill);
            if (xp <= start) continue;

            SkillLine line = new SkillLine();
            line.skill = skill;
            line.level = Experience.getLevelForXp(xp);
            line.levelsGained = line.level - Experience.getLevelForXp(start);
            line.xpGained = xp - start;
            if (line.level >= Experience.MAX_REAL_LEVEL) {
                line.progress = 1;
            } else {
                int from = Experience.getXpForLevel(line.level);
                int to = Experience.getXpForLevel(line.level + 1);
                line.progress = Math.min(1, Math.max(0, (xp - from) / (double) (to - from)));
            }
            lines.add(line);
        }
        return lines;
    }

    private long totalXpGained() {
        long total = 0;
        for (Skill skill : WarriorsGuildStats.TRACKED_SKILLS) {
            int start = stats.startXp(skill);
            if (start >= 0) total += Math.max(0, client.getSkillExperience(skill) - start);
        }
        return total;
    }

    private String currentState() {
        StateSnapshot<WarriorsGuildScript.State> snapshot = script.getSnapshot();
        return snapshot != null && snapshot.currentState() != null ? snapshot.currentState().name() : "";
    }

    // --- Drawing & formatting helpers ---

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

    private static Color tierColor(Defender d) {
        switch (d) {
            case BRONZE: return new Color(150, 100, 60);
            case IRON: return new Color(120, 120, 125);
            case STEEL: return new Color(160, 165, 175);
            case BLACK: return new Color(60, 60, 66);
            case MITHRIL: return new Color(80, 90, 150);
            case ADAMANT: return new Color(70, 120, 80);
            case RUNE: return new Color(70, 140, 170);
            default: return new Color(170, 50, 45);
        }
    }

    private static Color skillColor(Skill skill) {
        switch (skill) {
            case ATTACK: return new Color(230, 90, 80);
            case STRENGTH: return new Color(90, 200, 120);
            case DEFENCE: return new Color(100, 150, 240);
            case HITPOINTS: return new Color(240, 110, 160);
            case RANGED: return new Color(150, 200, 70);
            case MAGIC: return new Color(120, 130, 250);
            default: return ACCENT;
        }
    }

    private static String prettyState(String state) {
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
