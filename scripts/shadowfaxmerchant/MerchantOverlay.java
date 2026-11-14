package net.runelite.client.plugins.microbot.shadowfaxmerchant;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.shadowfaxmerchant.Opportunity.Strategy;
import net.runelite.client.plugins.microbot.shadowfaxmerchant.Position.Phase;
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
import java.util.List;

/**
 * Trading card drawn with Graphics2D: header with live state, session tiles, each open offer with its fill
 * progress, and the scanner's current best opportunities.
 */
public class MerchantOverlay extends Overlay {

    private static final int WIDTH = 270;
    private static final int PAD = 12;
    private static final int GAP = 6;
    private static final int HEADER_H = 40;
    private static final int TILE_H = 38;
    private static final int SECTION_H = 20;
    private static final int ROW_H = 24;
    private static final int LINE_H = 15;
    private static final int WATCH_ROWS = 4;

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
    private static final Color DIP = new Color(110, 180, 240);
    private static final Color SHADOW = new Color(0, 0, 0, 160);

    private final MerchantConfig config;
    private final MerchantStats stats;
    private final MerchantScript script;

    @Inject
    MerchantOverlay(MerchantPlugin plugin, MerchantConfig config, MerchantStats stats, MerchantScript script) {
        super(plugin);
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

        List<Position> positions = script.positions();
        List<Opportunity> watch = stats.opportunities();
        int offerRows = Math.max(1, positions.size());
        int watchRows = Math.max(1, Math.min(WATCH_ROWS, watch.size()));
        int height = PAD + HEADER_H
                + 2 * (TILE_H + GAP)
                + SECTION_H + offerRows * ROW_H + GAP
                + SECTION_H + watchRows * LINE_H + GAP
                + LINE_H
                + PAD;

        drawCard(g, height);
        int y = PAD;
        y = drawHeader(g, y);
        y = drawTiles(g, y);
        y = drawOffers(g, y, positions) + GAP;
        y = drawWatchlist(g, y, watch) + GAP;
        drawFooter(g, y);
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
        MerchantScript.State state = script.getCurrentState();
        g.setColor(state == MerchantScript.State.TRADE ? GOOD : state == MerchantScript.State.FINISH ? BAD : ACCENT_2);
        g.fillOval(PAD, y + 5, 8, 8);

        g.setFont(FontManager.getRunescapeBoldFont());
        text(g, "Shadowfax Merchant", PAD + 14, y + 14, TEXT);
        g.setFont(FontManager.getRunescapeSmallFont());
        textRight(g, config.windDown() ? "winding down" : "v" + MerchantPlugin.VERSION, WIDTH - PAD, y + 13,
                config.windDown() ? ACCENT_2 : MUTED);

        String away = script.awayStatus();
        String status = away != null ? away : Microbot.status != null ? Microbot.status : "";
        String line = state == null ? status : pretty(state.name()) + "  ·  " + status;
        text(g, ellipsize(g, line, WIDTH - 2 * PAD), PAD, y + 32, MUTED);
        return y + HEADER_H;
    }

    private int drawTiles(Graphics2D g, int y) {
        int cols = 3;
        int tileW = (WIDTH - 2 * PAD - (cols - 1) * GAP) / cols;
        long profit = stats.realisedProfit();
        long inUse = 0;
        for (Position p : script.positions()) inUse += p.capitalInUse();
        String winRate = stats.closed() == 0 ? "-" : Math.round(100.0 * stats.wins() / stats.closed()) + "%";
        String[][] tiles = {
                {"RUNTIME", formatDuration(stats.runtime())},
                {"PROFIT", signed(profit)},
                {"PROFIT / HR", signed(stats.perHour(profit))},
                {"IN USE", gp(inUse) + " / " + gp(config.capital())},
                {"FLIPS", String.valueOf(stats.closed())},
                {"WIN RATE", winRate},
        };
        for (int i = 0; i < tiles.length; i++) {
            int x = PAD + (i % cols) * (tileW + GAP);
            int ty = y + (i / cols) * (TILE_H + GAP);
            g.setColor(TILE_BG);
            g.fill(new RoundRectangle2D.Float(x, ty, tileW, TILE_H, 8, 8));
            g.setFont(FontManager.getRunescapeSmallFont());
            text(g, ellipsize(g, tiles[i][0], tileW - 12), x + 6, ty + 13, MUTED);
            g.setFont(FontManager.getRunescapeBoldFont());
            Color valueColor = (i == 1 || i == 2) ? (profit > 0 ? GOOD : profit < 0 ? BAD : TEXT) : TEXT;
            text(g, ellipsize(g, tiles[i][1], tileW - 12), x + 6, ty + 31, valueColor);
        }
        return y + 2 * (TILE_H + GAP);
    }

    private int drawOffers(Graphics2D g, int y, List<Position> positions) {
        g.setFont(FontManager.getRunescapeSmallFont());
        text(g, "OFFERS", PAD, y + 13, MUTED);
        textRight(g, positions.size() + " open", WIDTH - PAD, y + 13, MUTED);
        y += SECTION_H;
        if (positions.isEmpty()) {
            text(g, config.mode() == MerchantConfig.Mode.SCAN_ONLY ? "Scan only: not trading" : "None yet", PAD, y + 11, MUTED);
            return y + ROW_H;
        }
        for (Position p : positions) {
            boolean buying = p.phase == Phase.BUYING;
            Color tag = p.strategy == Strategy.DIP ? DIP : ACCENT;
            g.setColor(buying ? ACCENT_2 : GOOD);
            g.fillOval(PAD, y + 4, 6, 6);

            String right;
            double progress;
            if (buying) {
                int got = p.bought - p.baseBought;
                right = "Buy " + compact(got) + "/" + compact(Math.max(p.offerQty, 1)) + " @ " + gp(p.offerPrice);
                progress = p.offerQty > 0 ? got / (double) p.offerQty : 0;
            } else {
                int got = p.sold - p.baseSold;
                right = p.hasLiveOffer()
                        ? "Sell " + compact(got) + "/" + compact(Math.max(p.offerQty, 1)) + " @ " + gp(p.offerPrice)
                        : "Listing " + compact(p.held());
                progress = p.offerQty > 0 ? got / (double) p.offerQty : 0;
            }
            g.setFont(FontManager.getRunescapeSmallFont());
            int rightW = g.getFontMetrics().stringWidth(right);
            textRight(g, right, WIDTH - PAD, y + 10, TEXT);
            String name = (p.strategy == Strategy.DIP ? "[D] " : "") + p.name;
            text(g, ellipsize(g, name, WIDTH - 2 * PAD - 12 - rightW - 8), PAD + 10, y + 10, tag);

            int barW = WIDTH - 2 * PAD - 10;
            g.setColor(BAR_BG);
            g.fill(new RoundRectangle2D.Float(PAD + 10, y + 15, barW, 4, 4, 4));
            int fill = (int) Math.round(barW * Math.min(1, Math.max(0, progress)));
            if (fill > 0) {
                g.setColor(buying ? ACCENT_2 : GOOD);
                g.fill(new RoundRectangle2D.Float(PAD + 10, y + 15, Math.max(fill, 4), 4, 4, 4));
            }
            y += ROW_H;
        }
        return y;
    }

    private int drawWatchlist(Graphics2D g, int y, List<Opportunity> watch) {
        g.setFont(FontManager.getRunescapeSmallFont());
        g.setColor(CARD_BORDER);
        g.drawLine(PAD, y - 2, WIDTH - PAD, y - 2);
        text(g, "BEST RIGHT NOW", PAD, y + 13, MUTED);
        textRight(g, stats.opportunities().size() + " found", WIDTH - PAD, y + 13, MUTED);
        y += SECTION_H;
        if (watch.isEmpty()) {
            text(g, script.feed().isReady() ? "Nothing worth trading" : "Loading prices…", PAD, y + 11, MUTED);
            return y + LINE_H;
        }
        for (int i = 0; i < Math.min(WATCH_ROWS, watch.size()); i++) {
            Opportunity o = watch.get(i);
            String right = "+" + gp(o.expectedProfit) + "  " + String.format("%.1f%%", o.roi * 100);
            int rightW = g.getFontMetrics().stringWidth(right);
            textRight(g, right, WIDTH - PAD, y + 11, GOOD);
            String name = (o.strategy == Strategy.DIP ? "[D] " : "") + o.name;
            text(g, ellipsize(g, name, WIDTH - 2 * PAD - rightW - 8), PAD, y + 11,
                    o.strategy == Strategy.DIP ? DIP : TEXT);
            y += LINE_H;
        }
        return y;
    }

    private void drawFooter(Graphics2D g, int y) {
        g.setFont(FontManager.getRunescapeSmallFont());
        MarketFeed feed = script.feed();
        String line;
        if (feed.getLastError() != null) {
            line = "Price API: " + feed.getLastError();
        } else if (feed.getLastUpdatedAt() == 0) {
            line = "Waiting for prices";
        } else {
            long ago = (System.currentTimeMillis() - feed.getLastUpdatedAt()) / 1000;
            line = String.format("Prices %ds ago  ·  %,d items  ·  tax paid %s", ago, stats.scannedItems(), gp(stats.taxPaid()));
        }
        text(g, ellipsize(g, line, WIDTH - 2 * PAD), PAD, y + 11, feed.getLastError() != null ? BAD : MUTED);
    }

    // --- Helpers ---

    private static String signed(long n) {
        return (n > 0 ? "+" : n < 0 ? "-" : "") + gp(Math.abs(n));
    }

    private static String gp(long n) {
        if (n >= 1_000_000_000) return String.format("%.2fb", n / 1_000_000_000.0);
        if (n >= 1_000_000) return String.format("%.1fm", n / 1_000_000.0);
        if (n >= 10_000) return String.format("%.0fk", n / 1_000.0);
        return String.format("%,d", n);
    }

    private static String compact(long n) {
        return n >= 10_000 ? String.format("%.0fk", n / 1_000.0) : String.format("%,d", n);
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
        if (maxWidth <= 0) return "";
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
