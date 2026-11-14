package net.runelite.client.plugins.microbot.shadowfaxmerchant;

import net.runelite.client.plugins.microbot.shadowfaxmerchant.Opportunity.Strategy;
import net.runelite.client.plugins.microbot.util.market.GeTax;
import net.runelite.client.plugins.microbot.util.market.WikiPriceApi.Average;
import net.runelite.client.plugins.microbot.util.market.WikiPriceApi.ItemMapping;
import net.runelite.client.plugins.microbot.util.market.WikiPriceApi.Latest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/**
 * Ranks every item on the Grand Exchange as a flip or a dip buy, by expected after-tax profit per hour of slot time.
 *
 * <h3>How the market works for us</h3>
 * The wiki splits trades into "high" (someone bought instantly, filling a sell offer) and "low" (someone sold
 * instantly, filling a buy offer). Our buy offer sits at the best bid and fills only when sellers dump into it, so
 * its fill rate is the low-side volume; our sell offer sits at the ask and fills at the high-side volume. We assume
 * we only get a share ({@link Params#flowShare}) of that flow, since other flippers sit at the same prices.
 *
 * <h3>Flips</h3>
 * Bid one coin over the last instant-sell price, ask one coin under the last instant-buy price. Both are capped by
 * the hour's averages, so a single odd trade doesn't make a spread look wider than it is. An item qualifies when:
 * <ul>
 *   <li>the spread pays after tax, now and on average over the last day (a spread that's only there this minute
 *       won't still be there when we sell);</li>
 *   <li>both sides traded recently and trade often enough to fill within the target time;</li>
 *   <li>the price isn't sliding (hour average well under the day's): buying into a falling price is how flips lose
 *       money.</li>
 * </ul>
 * Quantity is the smallest of: our capital per item, what's left of the 4-hour buy limit, and what our share of
 * the flow can fill in the target time.
 *
 * <h3>Dips ("sleepers")</h3>
 * An item trading well under its normal price, where "normal" is the average of the {@link MarketFeed#HISTORY_DAYS}
 * days before the last full day, and those days were steady (low day-to-day spread) so the drop really stands out
 * (several standard deviations). The drop also has to have stopped: the last 5 minutes no lower than the last hour.
 * We buy at the bid and sell partway back toward normal. A drop on huge volume is often news (an update made the
 * item worth less for good), so it's discounted rather than trusted.
 */
public final class MarketScanner {

    /** Tunables; defaults suit a ~25m stack on a members world. */
    public static final class Params {
        public long capital = 25_000_000;
        public boolean members = true;
        /** Most of the capital any one flip may use. */
        public double maxItemShare = 0.20;
        public double minRoi = 0.004;
        /** Spreads wider than this rarely fill at the ask; ignored as bad data. */
        public double maxRoi = 0.25;
        public long minFlipProfit = 15_000;
        /** How long a flip should take to fill both ways; sizes quantities. */
        public int targetMinutes = 45;
        /** Share of the instant buy/sell flow we expect our offers to catch. */
        public double flowShare = 0.12;
        public double minFlowPerHour = 8;
        public int maxStaleMinutes = 30;
        public long minPrice = 10;

        public boolean dips = true;
        public double dipShare = 0.15;
        public double minDipPct = 0.08;
        public double maxDipPct = 0.35;
        public double minDipRoi = 0.03;
        public double maxBaselineSpread = 0.07;
        public double dipHoldHours = 12;
        /** How far back toward the normal price we aim to sell a dip. */
        public double dipRecovery = 0.6;

        /** Item id → units still buyable under its 4-hour limit; the scanner caps quantities with it. */
        public IntUnaryOperator limitRemaining = id -> Integer.MAX_VALUE;
        public Set<Integer> excluded = Set.of();
    }

    /** A bid/ask pair for one item, one coin inside the recent spread. */
    public static final class Quote {
        public final long bid;
        public final long ask;

        Quote(long bid, long ask) {
            this.bid = bid;
            this.ask = ask;
        }
    }

    // Bonds need extra steps to trade and are a different market.
    private static final Set<Integer> NEVER = Set.of(13190, 13192);

    private MarketScanner() {
    }

    /** Every item worth trading right now, best first, one entry per item. */
    public static List<Opportunity> scan(MarketFeed feed, Params p) {
        long now = System.currentTimeMillis() / 1000;
        Map<Integer, Opportunity> best = new HashMap<>();
        for (ItemMapping item : feed.getMapping().values()) {
            if (!eligible(item, p)) continue;
            Latest latest = feed.getLatest().get(item.id);
            Average hour = feed.getHour().get(item.id);
            Average day = feed.getDay().get(item.id);
            Average five = feed.getFiveMinute().get(item.id);
            if (latest == null || hour == null || day == null) continue;

            Opportunity flip = flip(feed, item, latest, hour, day, p, now);
            Opportunity dip = p.dips ? dip(item, latest, five, hour, day, feed.history(item.id), p, now) : null;
            for (Opportunity o : new Opportunity[]{flip, dip}) {
                if (o == null) continue;
                Opportunity have = best.get(item.id);
                if (have == null || o.score > have.score) best.put(item.id, o);
            }
        }
        List<Opportunity> out = new ArrayList<>(best.values());
        out.sort(Comparator.comparingDouble((Opportunity o) -> -o.score));
        return out;
    }

    /** Fresh bid/ask for an item we already trade, for repricing. Null without usable prices. */
    public static Quote quote(MarketFeed feed, int itemId) {
        return quote(feed.getLatest().get(itemId), feed.getHour().get(itemId));
    }

    static Quote quote(Latest latest, Average hour) {
        if (latest == null || latest.high <= 0 || latest.low <= 0) return null;
        long low = latest.low;
        long high = latest.high;
        if (hour != null && hour.avgLow > 0 && low > hour.avgLow * 1.03) low = Math.round(hour.avgLow);
        if (hour != null && hour.avgHigh > 0) high = Math.min(high, Math.round(hour.avgHigh));
        long bid = low + 1;
        long ask = high - 1;
        return ask > bid ? new Quote(bid, ask) : null;
    }

    // --- Flips ---

    private static Opportunity flip(MarketFeed feed, ItemMapping item, Latest latest, Average hour, Average day,
                                    Params p, long now) {
        double staleMin = staleMinutes(latest, now);
        if (staleMin > p.maxStaleMinutes) return null;
        Quote q = quote(latest, hour);
        if (q == null || q.bid < p.minPrice) return null;

        long margin = GeTax.afterTax(item.id, q.ask) - q.bid;
        double roi = margin / (double) q.bid;
        if (margin < 1 || roi < p.minRoi || roi > p.maxRoi) return null;
        // With a day of 5-minute history: expect the margin halfway between now and its usual size, and drop items
        // whose spread is usually closed. Without it the item can still rank, discounted, until it's checked.
        MarketFeed.SpreadProfile profile = feed.profile(item.id);
        if (profile != null) {
            if (profile.windows < 24 || profile.positiveShare < 0.5) return null;
            margin = Math.round(0.5 * margin + 0.5 * Math.max(0, profile.medianMargin));
            roi = margin / (double) q.bid;
            if (margin < 1 || roi < p.minRoi) return null;
        }
        // The spread has to be there on average too, not just in the latest two trades.
        if (day.avgHigh <= 0 || day.avgLow <= 0
                || GeTax.afterTax(item.id, Math.round(day.avgHigh)) - day.avgLow <= 0) return null;

        double buyFlow = flow(hour.lowVolume, day.lowVolume);
        double sellFlow = flow(hour.highVolume, day.highVolume);
        if (buyFlow < p.minFlowPerHour || sellFlow < p.minFlowPerHour) return null;

        // Sliding price: the hour's average well under the day's. Don't catch falling knives.
        double move = (hour.mid() - day.mid()) / day.mid();
        if (move < -0.05 || Math.abs(move) > 0.12) return null;

        int qty = quantity(item, q.bid, p.capital * p.maxItemShare, buyFlow, sellFlow, p.targetMinutes / 60.0, p);
        if (qty <= 0) return null;
        long profit = margin * qty;
        if (profit < p.minFlipProfit) return null;
        double hours = Math.max(0.1, qty / (p.flowShare * buyFlow) + qty / (p.flowShare * sellFlow));

        double confidence = 1.0;
        confidence *= 1 - 0.5 * Math.min(1, staleMin / p.maxStaleMinutes);
        confidence *= Math.max(0.35, 1 - 4 * Math.abs(move));
        if (roi > 0.06) confidence *= 0.75;
        if (roi > 0.12) confidence *= 0.6;
        double daySpread = day.avgHigh - day.avgLow;
        if (daySpread > 0 && (q.ask - q.bid) > 3 * daySpread) confidence *= 0.7;
        // A spread of a few coins means long queues of identical offers at both prices; ours waits its turn.
        if (q.ask - q.bid <= 3) confidence *= 0.6;
        if (profile == null) confidence *= 0.7;
        else confidence *= Math.min(1, 0.5 + profile.positiveShare / 2);

        String reason = String.format("margin %.1f%% · %s/%s per h%s", roi * 100, compact(buyFlow), compact(sellFlow),
                profile != null ? String.format(" · open %.0f%% of day", profile.positiveShare * 100) : " · unchecked");
        return new Opportunity(item.id, item.name, Strategy.FLIP, q.bid, q.ask, margin, roi, qty, profit, hours,
                profit * confidence / hours, buyFlow, sellFlow, reason, profile != null);
    }

    // --- Dips ---

    private static Opportunity dip(ItemMapping item, Latest latest, Average five, Average hour, Average day,
                                   List<Average> history, Params p, long now) {
        if (history.size() < MarketFeed.HISTORY_DAYS - 2) return null;
        double mean = 0;
        double baseVolume = 0;
        for (Average a : history) {
            mean += a.mid();
            baseVolume += a.volume();
        }
        mean /= history.size();
        baseVolume /= history.size();
        double var = 0;
        for (Average a : history) var += Math.pow(a.mid() - mean, 2);
        double std = Math.sqrt(var / history.size());
        if (mean <= 0 || std / mean > p.maxBaselineSpread) return null;

        if (staleMinutes(latest, now) > 60) return null;
        double nowMid = (latest.high + latest.low) / 2.0;
        double dip = (mean - nowMid) / mean;
        if (dip < p.minDipPct || dip > p.maxDipPct) return null;
        double z = std > 0 ? (mean - nowMid) / std : 99;
        if (z < 2) return null;

        // Stopped falling: the last 5 minutes and the latest trades aren't below the hour.
        double lastFive = five != null && five.mid() > 0 ? five.mid() : nowMid;
        if (lastFive < hour.mid() * 0.995 || (hour.avgLow > 0 && latest.low < hour.avgLow * 0.985)) return null;
        // Still trades (a dead market can't be sold back into).
        if (day.volume() < 0.4 * baseVolume) return null;

        Quote q = quote(latest, hour);
        if (q == null || q.bid < p.minPrice) return null;
        long target = Math.max(q.ask, Math.round(nowMid + p.dipRecovery * (mean - nowMid)));
        long margin = GeTax.afterTax(item.id, target) - q.bid;
        double roi = margin / (double) q.bid;
        if (roi < p.minDipRoi) return null;

        double buyFlow = flow(hour.lowVolume, day.lowVolume);
        double sellFlow = flow(hour.highVolume, day.highVolume);
        if (buyFlow < p.minFlowPerHour / 2) return null;
        int qty = quantity(item, q.bid, p.capital * p.dipShare, buyFlow, sellFlow, 2.0, p);
        if (qty <= 0) return null;
        long profit = margin * qty;

        double volumeSpike = baseVolume > 0 ? day.volume() / baseVolume : 1;
        double chance = volumeSpike > 2.5 ? 0.4 : 0.6;
        String reason = String.format("-%.0f%% vs 7d avg %s · z %.1f%s", dip * 100, compact(mean), z,
                volumeSpike > 2.5 ? " · heavy selling" : " · steadying");
        return new Opportunity(item.id, item.name, Strategy.DIP, q.bid, target, margin, roi, qty, profit,
                p.dipHoldHours, profit * chance / p.dipHoldHours, buyFlow, sellFlow, reason, true);
    }

    // --- Helpers ---

    private static boolean eligible(ItemMapping item, Params p) {
        if (item.limit <= 0 || NEVER.contains(item.id) || p.excluded.contains(item.id)) return false;
        return p.members || !item.members;
    }

    /** Smallest of capital, remaining buy limit and what our share of the flow fills in {@code hours}. */
    private static int quantity(ItemMapping item, long price, double capital, double buyFlow, double sellFlow,
                                double hours, Params p) {
        long byCapital = (long) (capital / price);
        long byLimit = Math.min(item.limit, p.limitRemaining.applyAsInt(item.id));
        long byFlow = Math.max(1, (long) (p.flowShare * Math.min(buyFlow, sellFlow) * hours));
        return (int) Math.max(0, Math.min(byCapital, Math.min(byLimit, byFlow)));
    }

    /** Per-hour volume: the last hour and the day's hourly average, weighted equally (one quiet hour isn't a trend). */
    private static double flow(long hourVolume, long dayVolume) {
        double perHourOfDay = dayVolume / 24.0;
        if (hourVolume <= 0) return perHourOfDay * 0.5;
        if (dayVolume <= 0) return hourVolume * 0.5;
        return (hourVolume + perHourOfDay) / 2;
    }

    /** Minutes since the older of the last instant buy and instant sell. */
    private static double staleMinutes(Latest latest, long now) {
        if (latest.highTime <= 0 || latest.lowTime <= 0) return Double.MAX_VALUE;
        return (now - Math.min(latest.highTime, latest.lowTime)) / 60.0;
    }

    static String compact(double n) {
        if (n >= 1_000_000) return String.format("%.1fm", n / 1_000_000);
        if (n >= 10_000) return String.format("%.0fk", n / 1_000);
        if (n >= 1_000) return String.format("%.1fk", n / 1_000);
        return String.format("%.0f", n);
    }
}
