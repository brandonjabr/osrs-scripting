package net.runelite.client.plugins.microbot.shadowfaxhillgiants;

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
 * Stats card drawn straight with Graphics2D: header with live state, a grid of session tiles,
 * the most valuable loot with estimated GE value, and per-skill xp rows with progress to the next level.
 */
public class HillGiantOverlay extends Overlay {

    private static final int WIDTH = 250;
    private static final int PAD = 12;
    private static final int GAP = 6;
    private static final int HEADER_H = 40;
    private static final int TILE_H = 38;
    private static final int SECTION_H = 20;
    private static final int SKILL_ROW_H = 30;
    private static final int LOOT_ROW_H = 15;
    private static final int LOOT_ROWS = 5;
    private static final int FOOTER_LINE_H = 15;

    private static final Color CARD_BG = new Color(16, 18, 24, 225);
    private static final Color CARD_BORDER = new Color(255, 255, 255, 30);
    private static final Color TILE_BG = new Color(255, 255, 255, 14);
    private static final Color BAR_BG = new Color(255, 255, 255, 22);
    private static final Color TEXT = new Color(236, 238, 242);
    private static final Color MUTED = new Color(150, 156, 170);
    private static final Color ACCENT = new Color(120, 200, 110);
    private static final Color ACCENT_2 = new Color(240, 190, 90);
    private static final Color SHADOW = new Color(0, 0, 0, 160);

    private final Client client;
    private final HillGiantConfig config;
    private final HillGiantStats stats;
    private final HillGiantScript script;

    @Inject
    HillGiantOverlay(HillGiantPlugin plugin, Client client, HillGiantConfig config,
                      HillGiantStats stats, HillGiantScript script) {
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
        List<HillGiantStats.LootLine> loot = stats.topLoot(LOOT_ROWS);
        int tileRows = 3;
        int height = PAD + HEADER_H
                + tileRows * (TILE_H + GAP)
                + SECTION_H + Math.max(1, loot.size()) * LOOT_ROW_H + GAP
                + SECTION_H + Math.max(1, skills.size()) * SKILL_ROW_H
                + GAP + FOOTER_LINE_H + PAD;

        drawCard(g, height);
        int y = PAD;
        y = drawHeader(g, y);
        y = drawTiles(g, y);
        y = drawLoot(g, y, loot) + GAP;
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
        // Accent stripe along the top edge.
        g.setPaint(new GradientPaint(0, 0, ACCENT, WIDTH, 0, ACCENT_2));
        g.fill(new RoundRectangle2D.Float(PAD, 0, WIDTH - 2 * PAD, 3, 3, 3));
    }

    private int drawHeader(Graphics2D g, int y) {
        String state = currentState();
        boolean fighting = "FIGHT".equals(state);

        // Status dot: green while fighting, amber while travelling or banking.
        g.setColor(fighting ? ACCENT : ACCENT_2);
        g.fillOval(PAD, y + 5, 8, 8);

        g.setFont(FontManager.getRunescapeBoldFont());
        text(g, "Shadowfax Hill Giants", PAD + 14, y + 14, TEXT);

        g.setFont(FontManager.getRunescapeSmallFont());
        String version = "v" + HillGiantPlugin.VERSION;
        textRight(g, version, WIDTH - PAD, y + 13, MUTED);

        String status = Microbot.status != null ? Microbot.status : "";
        String line = state.isEmpty() ? status : prettyState(state) + "  ·  " + status;
        text(g, ellipsize(g, line, WIDTH - 2 * PAD), PAD, y + 32, MUTED);

        return y + HEADER_H;
    }

    private int drawTiles(Graphics2D g, int y) {
        int cols = 3;
        int tileW = (WIDTH - 2 * PAD - (cols - 1) * GAP) / cols;
        long xpGained = totalXpGained();

        String[][] tiles = {
                {"RUNTIME", formatDuration(stats.runtime())},
                {"KILLS", formatNumber(stats.kills())},
                {"KILLS / HR", formatNumber(stats.perHour(stats.kills()))},
                {"LOOT (EST.)", formatGp(stats.lootValue())},
                {"GP / HR", formatGp(stats.perHour(stats.lootValue()))},
                {"XP / HR", formatNumber(stats.perHour(xpGained))},
                {"BONES BURIED", formatNumber(stats.bonesBuried())},
                {"BONES / HR", formatNumber(stats.perHour(stats.bonesBuried()))},
                {"BANK TRIPS", formatNumber(stats.bankTrips())},
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

    private int drawLoot(Graphics2D g, int y, List<HillGiantStats.LootLine> loot) {
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "LOOT", PAD, y + 13, MUTED);
        textRight(g, formatNumber(stats.itemsLooted()) + " items  ·  " + formatGp(stats.lootValue()),
                WIDTH - PAD, y + 13, MUTED);
        y += SECTION_H;

        if (loot.isEmpty()) {
            text(g, "Nothing worth picking up yet…", PAD, y + 11, MUTED);
            return y + LOOT_ROW_H;
        }

        for (HillGiantStats.LootLine line : loot) {
            g.setFont(FontManager.getRunescapeFont());
            String value = formatGp(line.value.get());
            String qty = "x" + line.quantity.get();
            int valueW = g.getFontMetrics().stringWidth(value);
            int qtyX = WIDTH - PAD - valueW - 44;
            text(g, ellipsize(g, displayName(line.name), qtyX - PAD - 4), PAD, y + 11, TEXT);
            text(g, qty, qtyX, y + 11, MUTED);
            textRight(g, value, WIDTH - PAD, y + 11, ACCENT_2);
            y += LOOT_ROW_H;
        }
        return y;
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

        text(g, "Prayer  " + client.getRealSkillLevel(Skill.PRAYER) + "  ·  Recoveries  " + stats.recoveries(), PAD, y + 11, MUTED);
        textRight(g, "Levels  +" + totalLevelsGained(), WIDTH - PAD, y + 11, TEXT);
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
        for (Skill skill : HillGiantStats.TRACKED_SKILLS) {
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
        for (Skill skill : HillGiantStats.TRACKED_SKILLS) {
            int start = stats.startXp(skill);
            if (start >= 0) total += Math.max(0, client.getSkillExperience(skill) - start);
        }
        return total;
    }

    private int totalLevelsGained() {
        int total = 0;
        for (Skill skill : HillGiantStats.TRACKED_SKILLS) {
            int start = stats.startXp(skill);
            if (start >= 0) {
                total += Experience.getLevelForXp(client.getSkillExperience(skill)) - Experience.getLevelForXp(start);
            }
        }
        return total;
    }

    private String currentState() {
        StateSnapshot<HillGiantScript.State> snapshot = script.getSnapshot();
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

    private static Color skillColor(Skill skill) {
        switch (skill) {
            case ATTACK: return new Color(230, 90, 80);
            case STRENGTH: return new Color(90, 200, 120);
            case DEFENCE: return new Color(100, 150, 240);
            case HITPOINTS: return new Color(240, 110, 160);
            case RANGED: return new Color(120, 190, 90);
            case MAGIC: return new Color(110, 130, 250);
            case PRAYER: return new Color(230, 220, 140);
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

    /** "Grimy ranarr weed" reads as "Ranarr weed" in the loot list. */
    private static String displayName(String name) {
        if (name.regionMatches(true, 0, "Grimy ", 0, 6) && name.length() > 6) {
            return Character.toUpperCase(name.charAt(6)) + name.substring(7);
        }
        return name;
    }

    private static String formatGp(long gp) {
        return formatNumber(gp) + " gp";
    }

    private static String formatNumber(long n) {
        if (n >= 1_000_000) return String.format("%.1fm", n / 1_000_000.0);
        if (n >= 10_000) return String.format("%.1fk", n / 1_000.0);
        return String.format("%,d", n);
    }
}
