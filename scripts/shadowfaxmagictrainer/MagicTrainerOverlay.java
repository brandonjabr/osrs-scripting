package net.runelite.client.plugins.microbot.shadowfaxmagictrainer;

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
import java.util.Map;

/**
 * Stats card drawn straight with Graphics2D: header with live state, a grid of session tiles, the spell in use
 * with progress to the next level and next unlock, rune counts, and the splash-gear check.
 */
public class MagicTrainerOverlay extends Overlay {

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
    private static final Color WARN = new Color(240, 110, 90);
    private static final Color SHADOW = new Color(0, 0, 0, 160);

    private final Client client;
    private final MagicTrainerConfig config;
    private final MagicTrainerStats stats;
    private final MagicTrainerScript script;

    @Inject
    MagicTrainerOverlay(MagicTrainerPlugin plugin, Client client, MagicTrainerConfig config,
                        MagicTrainerStats stats, MagicTrainerScript script) {
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

        Map<String, Integer> runes = stats.runes();
        int spellLines = 2 + (config.stopAtLevel() > 0 ? 1 : 0);
        int height = PAD + HEADER_H
                + 2 * (TILE_H + GAP)
                + SECTION_H + LINE_H + BAR_H + spellLines * LINE_H + GAP
                + SECTION_H + Math.max(1, runes.size() + 1) * LINE_H
                + GAP + 2 * LINE_H + PAD;

        drawCard(g, height);
        int y = PAD;
        y = drawHeader(g, y);
        y = drawTiles(g, y);
        y = drawSpell(g, y) + GAP;
        y = drawRunes(g, y, runes);
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

        // Status dot: green while splashing, amber while walking or restocking.
        g.setColor("SPLASH".equals(state) ? GOOD : ACCENT_2);
        g.fillOval(PAD, y + 5, 8, 8);

        g.setFont(FontManager.getRunescapeBoldFont());
        text(g, "Shadowfax Magic Trainer", PAD + 14, y + 14, TEXT);

        g.setFont(FontManager.getRunescapeSmallFont());
        textRight(g, "v" + MagicTrainerPlugin.VERSION, WIDTH - PAD, y + 13, MUTED);

        String status = Microbot.status != null ? Microbot.status : "";
        String line = state.isEmpty() ? status : prettyState(state) + "  ·  " + status;
        text(g, ellipsize(g, line, WIDTH - 2 * PAD), PAD, y + 32, MUTED);

        return y + HEADER_H;
    }

    private int drawTiles(Graphics2D g, int y) {
        int cols = 3;
        int tileW = (WIDTH - 2 * PAD - (cols - 1) * GAP) / cols;
        long xpGained = xpGained();
        int level = client.getRealSkillLevel(Skill.MAGIC);
        int levelsGained = stats.startXp() < 0 ? 0 : level - Experience.getLevelForXp(stats.startXp());

        String[][] tiles = {
                {"RUNTIME", formatDuration(stats.runtime())},
                {"CASTS", formatNumber(stats.casts())},
                {"CASTS / HR", formatNumber(stats.perHour(stats.casts()))},
                {"MAGIC", level + (levelsGained > 0 ? "  (+" + levelsGained + ")" : "")},
                {"XP GAINED", formatNumber(xpGained)},
                {"XP / HR", formatNumber(stats.perHour(xpGained))},
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

    private int drawSpell(Graphics2D g, int y) {
        SplashSpell spell = stats.spell();
        int xp = client.getSkillExperience(Skill.MAGIC);
        int level = Experience.getLevelForXp(xp);
        long xpPerHour = stats.perHour(xpGained());

        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "SPELL", PAD, y + 13, MUTED);
        y += SECTION_H;

        g.setFont(FontManager.getRunescapeFont());
        text(g, spell != null ? spell.displayName : "-", PAD, y + 11, ACCENT_2);
        if (spell != null) {
            textRight(g, formatXp(spell.xp) + " xp / cast", WIDTH - PAD, y + 11, MUTED);
        }
        y += LINE_H;

        // Progress to the next Magic level.
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
        textRight(g, eta(level + 1, xp, xpPerHour), WIDTH - PAD, y + 11, TEXT);
        y += LINE_H;

        SplashSpell unlock = nextUnlock(level);
        text(g, unlock != null ? "Unlocks " + unlock.displayName + " @ " + unlock.level : "Best spell unlocked", PAD, y + 11, MUTED);
        if (unlock != null) {
            textRight(g, eta(unlock.level, xp, xpPerHour), WIDTH - PAD, y + 11, TEXT);
        }
        y += LINE_H;

        if (config.stopAtLevel() > 0) {
            text(g, "Target level " + config.stopAtLevel(), PAD, y + 11, MUTED);
            textRight(g, eta(config.stopAtLevel(), xp, xpPerHour), WIDTH - PAD, y + 11, ACCENT_2);
            y += LINE_H;
        }
        return y;
    }

    private int drawRunes(Graphics2D g, int y, Map<String, Integer> runes) {
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "RUNES", PAD, y + 13, MUTED);
        y += SECTION_H;

        g.setFont(FontManager.getRunescapeFont());
        for (Map.Entry<String, Integer> rune : runes.entrySet()) {
            text(g, rune.getKey(), PAD, y + 11, TEXT);
            String count = rune.getValue() == Integer.MAX_VALUE ? "staff" : formatNumber(rune.getValue());
            textRight(g, count, WIDTH - PAD, y + 11, MUTED);
            y += LINE_H;
        }

        int castsLeft = stats.castsLeft();
        text(g, "Casts left", PAD, y + 11, TEXT);
        textRight(g, castsLeft == Integer.MAX_VALUE ? "unlimited" : formatNumber(castsLeft), WIDTH - PAD, y + 11,
                castsLeft > 0 ? GOOD : WARN);
        return y + LINE_H;
    }

    private void drawFooter(Graphics2D g, int y) {
        g.setFont(FontManager.getRunescapeSmallFont());
        g.setColor(CARD_BORDER);
        g.drawLine(PAD, y - 2, WIDTH - PAD, y - 2);

        Integer bonus = stats.magicBonus();
        boolean splashes = bonus != null && bonus <= MagicTrainerScript.SPLASH_MAX_MAGIC_BONUS;
        text(g, "Magic attack bonus", PAD, y + 11, MUTED);
        textRight(g, bonus == null ? "-" : bonus + (splashes ? "  (splashing)" : "  (needs -64)"),
                WIDTH - PAD, y + 11, splashes ? GOOD : WARN);
        y += LINE_H;

        text(g, "Spent on runes", PAD, y + 11, MUTED);
        textRight(g, formatNumber(stats.gpSpent()) + " gp", WIDTH - PAD, y + 11, TEXT);
    }

    // --- Data ---

    private long xpGained() {
        int start = stats.startXp();
        return start < 0 ? 0 : Math.max(0, client.getSkillExperience(Skill.MAGIC) - start);
    }

    /** First spell above the current level that the configured highest spell allows. */
    private SplashSpell nextUnlock(int level) {
        for (SplashSpell spell : SplashSpell.values()) {
            if (spell.ordinal() <= config.maxSpell().ordinal() && spell.level > level) return spell;
        }
        return null;
    }

    /** Time to reach a level at the current xp rate, e.g. "~1h 12m"; "done" once reached. */
    private static String eta(int targetLevel, int xp, long xpPerHour) {
        int target = Experience.getXpForLevel(Math.min(Math.max(targetLevel, 1), Experience.MAX_REAL_LEVEL));
        if (xp >= target) return "done";
        if (xpPerHour <= 0) return "…";
        long minutes = (target - xp) * 60L / xpPerHour;
        return minutes >= 60 ? "~" + minutes / 60 + "h " + minutes % 60 + "m" : "~" + Math.max(1, minutes) + "m";
    }

    private String currentState() {
        StateSnapshot<MagicTrainerScript.State> snapshot = script.getSnapshot();
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

    private static String prettyState(String state) {
        String s = state.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String formatXp(double xp) {
        return xp == Math.floor(xp) ? String.valueOf((long) xp) : String.valueOf(xp);
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
