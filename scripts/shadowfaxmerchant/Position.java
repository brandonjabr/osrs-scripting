package net.runelite.client.plugins.microbot.shadowfaxmerchant;

import net.runelite.client.plugins.microbot.shadowfaxmerchant.Opportunity.Strategy;
import net.runelite.client.plugins.microbot.util.market.GeTax;

/**
 * One item we're trading, from the first buy offer to the last coin of the sale. Plain fields so Gson can save it
 * between sessions: a restart mid-flip still knows what the items cost and what they're meant to sell for.
 * <p>
 * Totals ({@code bought}, {@code spent}, {@code sold}, {@code received}) cover finished offers; the live offer's
 * fills are added on top through the {@code base*} values captured when it was placed, since the GE reports an
 * offer's fills as running totals.
 */
public final class Position {

    public enum Phase {
        /** A buy offer is up (or about to be placed). */
        BUYING,
        /** Items held, a sell offer is up or about to be. */
        SELLING,
        /** Finished; kept briefly for the overlay, then dropped. */
        DONE
    }

    public int itemId;
    public String name;
    public Strategy strategy;
    public Phase phase = Phase.BUYING;

    public int targetQty;
    public long plannedSell;

    public int bought;
    public long spent;
    public int sold;
    /** Coins received before tax. */
    public long received;

    /** GE slot (0-7) holding our live offer, or -1. */
    public int slot = -1;
    public long offerPrice;
    public int offerQty;
    public long offerPlacedAt;
    public int baseBought;
    public long baseSpent;
    public int baseSold;
    public long baseReceived;
    public int reprices;
    /** Next time the live offer is checked for staleness or a stop-loss. */
    public long reviewAt;
    /** Set when a buy offer is cancelled to bid again higher, so an unfilled cancel doesn't close the position. */
    public boolean rebid;
    /** Ticks the items to sell weren't in the inventory. */
    public int missingTries;
    /** Why it closed or was last repriced, for the overlay and the trade log. */
    public String note = "";

    public long openedAt;
    public long firstFilledAt;
    public long lastFillAt;
    public long closedAt;

    Position() {
    }

    static Position open(Opportunity o) {
        Position p = new Position();
        p.itemId = o.itemId;
        p.name = o.name;
        p.strategy = o.strategy;
        p.targetQty = o.quantity;
        p.plannedSell = o.sellPrice;
        p.openedAt = System.currentTimeMillis();
        return p;
    }

    /** Items bought and not yet sold (in the inventory or in a sell offer). */
    public int held() {
        return bought - sold;
    }

    public long averageCost() {
        return bought == 0 ? 0 : (long) Math.ceil(spent / (double) bought);
    }

    /** Lowest sell price that doesn't lose money after tax. */
    public long breakEven() {
        return GeTax.breakEvenPrice(itemId, Math.max(1, averageCost()));
    }

    /** Tax on what has sold so far, at the average sell price. */
    public long taxPaid() {
        if (sold == 0) return 0;
        long avg = received / sold;
        return GeTax.tax(itemId, avg) * sold;
    }

    /** Realised profit on the units sold so far, after tax. */
    public long realisedProfit() {
        if (sold == 0) return 0;
        return received - taxPaid() - averageCost() * sold;
    }

    /** Coins tied up: what the held items cost plus what an open buy offer still reserves. */
    public long capitalInUse() {
        long reserved = phase == Phase.BUYING && slot >= 0 ? offerPrice * Math.max(0, offerQty - (bought - baseBought)) : 0;
        return averageCost() * held() + reserved;
    }

    public boolean hasLiveOffer() {
        return slot >= 0;
    }

    void clearOffer() {
        slot = -1;
        offerPrice = 0;
        offerQty = 0;
        offerPlacedAt = 0;
    }
}
