package net.runelite.client.plugins.microbot.shadowfaxgemcrab;

import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.statemachine.StateSnapshot;
import net.runelite.client.plugins.microbot.util.combat.WeaponStyles;
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
 * Stats card drawn straight with Graphics2D: header with live state, session tiles, the crab's burrow timer and
 * current training style, gems mined with estimated GE value, and per-skill xp rows with progress to the next level.
 */
public class GemCrabOverlay extends Overlay {

    private static final int WIDTH = 250;
    private static final int PAD = 12;
    private static final int GAP = 6;
    private static final int HEADER_H = 40;
    private static final int TILE_H = 38;
    private static final int SECTION_H = 20;
    private static final int CRAB_H = 44;
    private static final int SKILL_ROW_H = 30;
    private static final int GEM_ROW_H = 15;
    private static final int GEM_ROWS = 4;
    private static final int FOOTER_LINE_H = 15;
    // The crab's health bar is a ten-minute timer.
    private static final long CRAB_LIFETIME_S = 600;

    private static final Color CARD_BG = new Color(16, 18, 24, 225);
    private static final Color CARD_BORDER = new Color(255, 255, 255, 30);
    private static final Color TILE_BG = new Color(255, 255, 255, 14);
    private static final Color BAR_BG = new Color(255, 255, 255, 22);
    private static final Color TEXT = new Color(236, 238, 242);
    private static final Color MUTED = new Color(150, 156, 170);
    private static final Color ACCENT = new Color(90, 200, 190);
    private static final Color ACCENT_2 = new Color(190, 120, 240);
    private static final Color SHADOW = new Color(0, 0, 0, 160);

    private final Client client;
    private final GemCrabConfig config;
    private final GemCrabStats stats;
    private final GemCrabScript script;

    @Inject
    GemCrabOverlay(GemCrabPlugin plugin, Client client, GemCrabConfig config, GemCrabStats stats,
                   GemCrabScript script) {
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
        List<GemCrabStats.GemLine> gems = stats.topGems(GEM_ROWS);
        int tileRows = 3;
        int height = PAD + HEADER_H
                + tileRows * (TILE_H + GAP)
                + SECTION_H + CRAB_H + GAP
                + SECTION_H + Math.max(1, gems.size()) * GEM_ROW_H + GAP
                + SECTION_H + Math.max(1, skills.size()) * SKILL_ROW_H
                + GAP + FOOTER_LINE_H + PAD;

        drawCard(g, height);
        int y = PAD;
        y = drawHeader(g, y);
        y = drawTiles(g, y);
        y = drawCrab(g, y) + GAP;
        y = drawGems(g, y, gems) + GAP;
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
        boolean fighting = "FIGHT".equals(state);

        g.setColor(fighting ? ACCENT : ACCENT_2);
        g.fillOval(PAD, y + 5, 8, 8);

        g.setFont(FontManager.getRunescapeBoldFont());
        text(g, "Shadowfax Gem Crab", PAD + 14, y + 14, TEXT);

        g.setFont(FontManager.getRunescapeSmallFont());
        textRight(g, "v" + GemCrabPlugin.VERSION, WIDTH - PAD, y + 13, MUTED);

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
                {"CRABS", formatNumber(stats.crabs())},
                {"XP / HR", formatNumber(stats.perHour(xpGained))},
                {"GEMS", formatNumber(stats.gemCount())},
                {"GEM VALUE", formatGp(stats.gemValue())},
                {"GP / HR", formatGp(stats.perHour(stats.gemValue()))},
                {"TUNNELS", formatNumber(stats.tunnels())},
                {"BOOSTS", formatNumber(stats.potions())},
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

    /** Where the crab is, how long until it burrows, and which skill we're training. */
    private int drawCrab(Graphics2D g, int y) {
        GemCrabScript.Site site = script.currentSite;
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "CRAB", PAD, y + 13, MUTED);
        textRight(g, site != null ? site.label : "Not at a mine", WIDTH - PAD, y + 13, MUTED);
        y += SECTION_H;

        double health = script.crabHealth;
        g.setFont(FontManager.getRunescapeFont());
        if (health >= 0) {
            long left = Math.round(CRAB_LIFETIME_S * health / 100.0);
            text(g, String.format("%.0f%%", health), PAD, y + 11, TEXT);
            textRight(g, "burrows in ~" + left / 60 + ":" + String.format("%02d", left % 60), WIDTH - PAD, y + 11, MUTED);
        } else {
            text(g, "—", PAD, y + 11, MUTED);
        }

        int barY = y + 16;
        int barW = WIDTH - 2 * PAD;
        g.setColor(BAR_BG);
        g.fill(new RoundRectangle2D.Float(PAD, barY, barW, 6, 6, 6));
        int fill = health > 0 ? (int) Math.round(barW * health / 100.0) : 0;
        if (fill > 0) {
            g.setPaint(new GradientPaint(PAD, 0, ACCENT_2, PAD + barW, 0, ACCENT));
            g.fill(new RoundRectangle2D.Float(PAD, barY, Math.max(fill, 6), 6, 6, 6));
        }

        Skill trained = script.trainedSkill;
        WeaponStyles.Kind kind = script.combatKind;
        Skill boosted = kind == WeaponStyles.Kind.RANGED ? Skill.RANGED
                : kind == WeaponStyles.Kind.MAGIC ? Skill.MAGIC : Skill.STRENGTH;
        String boostLabel = boosted == Skill.RANGED ? "Rng " : boosted == Skill.MAGIC ? "Mag " : "Str ";
        int boost = client.getBoostedSkillLevel(boosted) - client.getRealSkillLevel(boosted);
        g.setFont(FontManager.getRunescapeSmallFont());
        String style = script.styleLabel;
        String boostText = boostLabel + (boost >= 0 ? "+" : "") + boost;
        String training = "Training  " + (trained != null ? trained.getName() : "—")
                + (style != null && !style.isEmpty() ? "  ·  " + style : "");
        int boostW = g.getFontMetrics().stringWidth(boostText);
        text(g, ellipsize(g, training, WIDTH - 2 * PAD - boostW - 8), PAD, y + 38, TEXT);
        textRight(g, boostText, WIDTH - PAD, y + 38, boost > 0 ? skillColor(boosted) : MUTED);
        return y + CRAB_H;
    }

    private int drawGems(Graphics2D g, int y, List<GemCrabStats.GemLine> gems) {
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "GEMS", PAD, y + 13, MUTED);
        textRight(g, formatNumber(stats.shellsMined()) + " shells  ·  " + formatGp(stats.gemValue()),
                WIDTH - PAD, y + 13, MUTED);
        y += SECTION_H;

        if (gems.isEmpty()) {
            text(g, config.mineShell() ? "No shells mined yet…" : "Shell mining is off", PAD, y + 11, MUTED);
            return y + GEM_ROW_H;
        }

        for (GemCrabStats.GemLine line : gems) {
            g.setFont(FontManager.getRunescapeFont());
            String value = formatGp(line.value.get());
            String qty = "x" + line.quantity.get();
            int valueW = g.getFontMetrics().stringWidth(value);
            int qtyX = WIDTH - PAD - valueW - 44;
            text(g, ellipsize(g, line.name, qtyX - PAD - 4), PAD, y + 11, TEXT);
            text(g, qty, qtyX, y + 11, MUTED);
            textRight(g, value, WIDTH - PAD, y + 11, ACCENT_2);
            y += GEM_ROW_H;
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
            text(g, left, PAD, y + 12, s.skill == script.trainedSkill ? ACCENT : TEXT);
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

        text(g, "Style swaps  " + stats.styleSwaps() + "  ·  Recoveries  " + stats.recoveries(), PAD, y + 11, MUTED);
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
        for (Skill skill : GemCrabStats.TRACKED_SKILLS) {
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
        for (Skill skill : GemCrabStats.TRACKED_SKILLS) {
            int start = stats.startXp(skill);
            if (start >= 0) total += Math.max(0, client.getSkillExperience(skill) - start);
        }
        return total;
    }

    private int totalLevelsGained() {
        int total = 0;
        for (Skill skill : GemCrabStats.TRACKED_SKILLS) {
            int start = stats.startXp(skill);
            if (start >= 0) {
                total += Experience.getLevelForXp(client.getSkillExperience(skill)) - Experience.getLevelForXp(start);
            }
        }
        return total;
    }

    private String currentState() {
        StateSnapshot<GemCrabScript.State> snapshot = script.getSnapshot();
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

    private static String formatGp(long gp) {
        return formatNumber(gp) + " gp";
    }

    private static String formatNumber(long n) {
        if (n >= 1_000_000) return String.format("%.1fm", n / 1_000_000.0);
        if (n >= 10_000) return String.format("%.1fk", n / 1_000.0);
        return String.format("%,d", n);
    }
}
