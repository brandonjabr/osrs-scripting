package net.runelite.client.plugins.microbot.shadowfaxmerchant;

/**
 * One trade the scanner recommends: buy {@link #quantity} at {@link #buyPrice}, sell at {@link #sellPrice}.
 * Profit figures are after the 2% GE tax.
 */
public final class Opportunity {

    public enum Strategy {
        /** Buy at the bid, sell at the ask, minutes to an hour or two. */
        FLIP,
        /** Bought well under its usual price after a drop that has stopped; sold as it recovers, hours to a day. */
        DIP
    }

    public final int itemId;
    public final String name;
    public final Strategy strategy;
    public final long buyPrice;
    public final long sellPrice;
    /** After-tax profit per item. */
    public final long marginEach;
    public final double roi;
    public final int quantity;
    public final long expectedProfit;
    public final double expectedHours;
    /** Expected profit per hour of slot time, discounted by how sure we are. Ranking key. */
    public final double score;
    /** Instant sells / instant buys per hour: how fast our buy and sell offers can fill. */
    public final double buyFlowPerHour;
    public final double sellFlowPerHour;
    /** Human-readable why, e.g. "spread 2.1% · 1.4k/h each side" or "-14% vs 7d avg · steadying". */
    public final String reason;
    /** Whether the margin was checked against the item's last 24 hours (flips only; dips use the daily history). */
    public final boolean checked;

    Opportunity(int itemId, String name, Strategy strategy, long buyPrice, long sellPrice, long marginEach,
                double roi, int quantity, long expectedProfit, double expectedHours, double score,
                double buyFlowPerHour, double sellFlowPerHour, String reason, boolean checked) {
        this.itemId = itemId;
        this.name = name;
        this.strategy = strategy;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.marginEach = marginEach;
        this.roi = roi;
        this.quantity = quantity;
        this.expectedProfit = expectedProfit;
        this.expectedHours = expectedHours;
        this.score = score;
        this.buyFlowPerHour = buyFlowPerHour;
        this.sellFlowPerHour = sellFlowPerHour;
        this.reason = reason;
        this.checked = checked;
    }

    public long capitalNeeded() {
        return buyPrice * quantity;
    }

    @Override
    public String toString() {
        return String.format("%-5s %-28s buy %,d sell %,d x%,d  +%,d (%.1f%%)  ~%.1fh  %,.0f gp/h  %s",
                strategy, name, buyPrice, sellPrice, quantity, expectedProfit, roi * 100, expectedHours, score, reason);
    }
}
