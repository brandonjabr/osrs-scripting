package net.runelite.client.plugins.microbot.shadowfaxmerchant;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.market.GeTax;
import net.runelite.client.plugins.microbot.util.market.WikiPriceApi;
import net.runelite.client.plugins.microbot.util.market.WikiPriceApi.Average;
import net.runelite.client.plugins.microbot.util.market.WikiPriceApi.ItemMapping;
import net.runelite.client.plugins.microbot.util.market.WikiPriceApi.Latest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps an up-to-date picture of the whole market from the wiki prices API, using only bulk requests:
 * <ul>
 *   <li>item mapping (names, buy limits, members) once per session;</li>
 *   <li>latest trades and 5-minute averages every minute (the API caches for a minute anyway);</li>
 *   <li>1-hour averages every 5 minutes, the last full day every 30 minutes;</li>
 *   <li>the {@link #HISTORY_DAYS} days before that, one bulk call per day, kept for the session since past days
 *       never change. They are the "normal price" baseline that dips are measured against.</li>
 * </ul>
 * {@link #refresh()} blocks on the network; call it from a background thread. Readers get immutable snapshots.
 */
@Slf4j
public class MarketFeed {

    static final int HISTORY_DAYS = 7;
    private static final long DAY_S = 86_400;
    private static final long LATEST_EVERY_MS = 60_000;
    private static final long HOUR_EVERY_MS = 5 * 60_000;
    private static final long DAY_EVERY_MS = 30 * 60_000;
    // Back off after a failed call, so an API outage isn't hammered.
    private static final long FAILURE_BACKOFF_MS = 2 * 60_000;

    @Getter
    private volatile Map<Integer, ItemMapping> mapping = Collections.emptyMap();
    @Getter
    private volatile Map<Integer, Latest> latest = Collections.emptyMap();
    @Getter
    private volatile Map<Integer, Average> fiveMinute = Collections.emptyMap();
    @Getter
    private volatile Map<Integer, Average> hour = Collections.emptyMap();
    @Getter
    private volatile Map<Integer, Average> day = Collections.emptyMap();
    // Day start (epoch seconds) -> that day's averages, oldest first.
    private final TreeMap<Long, Map<Integer, Average>> history = new TreeMap<>();
    @Getter
    private volatile long lastUpdatedAt;
    @Getter
    private volatile String lastError;

    /**
     * How an item's spread behaved over the last day, from its 5-minute history: the share of windows where selling at
     * the average high beat buying at the average low after tax, and the median of that margin. A spread that's wide
     * right now but usually isn't will have closed by the time we sell.
     */
    public static final class SpreadProfile {
        public final long fetchedAt;
        public final int windows;
        public final double positiveShare;
        public final long medianMargin;

        SpreadProfile(long fetchedAt, int windows, double positiveShare, long medianMargin) {
            this.fetchedAt = fetchedAt;
            this.windows = windows;
            this.positiveShare = positiveShare;
            this.medianMargin = medianMargin;
        }
    }

    private static final long PROFILE_TTL_MS = 15 * 60_000;
    private final Map<Integer, SpreadProfile> profiles = new ConcurrentHashMap<>();

    private long latestAt;
    private long hourAt;
    private long dayAt;
    private long failedAt;

    public boolean isReady() {
        return !mapping.isEmpty() && !latest.isEmpty() && !hour.isEmpty() && !day.isEmpty();
    }

    /** Fetches whatever is due. Returns true if anything new arrived. */
    public synchronized boolean refresh() {
        long now = System.currentTimeMillis();
        if (now - failedAt < FAILURE_BACKOFF_MS) return false;
        boolean changed = false;
        try {
            if (mapping.isEmpty()) {
                mapping = WikiPriceApi.mapping();
                changed = true;
            }
            if (now - latestAt >= LATEST_EVERY_MS) {
                latest = WikiPriceApi.latest();
                fiveMinute = WikiPriceApi.averages("5m");
                latestAt = now;
                changed = true;
            }
            if (now - hourAt >= HOUR_EVERY_MS) {
                hour = WikiPriceApi.averages("1h");
                hourAt = now;
                changed = true;
            }
            if (now - dayAt >= DAY_EVERY_MS) {
                day = WikiPriceApi.averages("24h");
                dayAt = now;
                changed = true;
            }
            changed |= fetchMissingHistoryDay();
            if (changed) lastUpdatedAt = now;
            lastError = null;
        } catch (Exception e) {
            failedAt = now;
            lastError = e.getMessage();
            log.warn("[Merchant] Price refresh failed: {}", e.getMessage());
        }
        return changed;
    }

    /**
     * One older day per refresh, newest missing first, until {@link #HISTORY_DAYS} days before the last full day
     * are cached. Day timestamps are UTC midnights (the API rejects anything else).
     */
    private boolean fetchMissingHistoryDay() throws Exception {
        long lastFullDay = latestDayStart();
        if (lastFullDay <= 0) return false;
        history.headMap(lastFullDay - HISTORY_DAYS * DAY_S).clear();
        for (int d = 1; d <= HISTORY_DAYS; d++) {
            long ts = lastFullDay - d * DAY_S;
            if (history.containsKey(ts)) continue;
            history.put(ts, WikiPriceApi.averagesAt("24h", ts));
            return true;
        }
        return false;
    }

    /** Start of the last full day the API has; the bulk 24h endpoint reports it. */
    private long latestDayStart() {
        for (Average a : day.values()) {
            return a.timestamp;
        }
        return 0;
    }

    /** The cached history days for one item, oldest first; days it didn't trade are skipped. */
    public synchronized List<Average> history(int itemId) {
        List<Average> out = new ArrayList<>(history.size());
        for (Map<Integer, Average> d : history.values()) {
            Average a = d.get(itemId);
            if (a != null && a.mid() > 0) out.add(a);
        }
        return out;
    }

    public synchronized int historyDays() {
        return history.size();
    }

    /** A fresh spread profile for the item, or null if it hasn't been fetched in the last 15 minutes. */
    public SpreadProfile profile(int itemId) {
        SpreadProfile p = profiles.get(itemId);
        return p != null && System.currentTimeMillis() - p.fetchedAt < PROFILE_TTL_MS ? p : null;
    }

    /**
     * Fetches spread profiles for up to {@code max} of {@code itemIds} that lack a fresh one, in order. One request per
     * item, so callers pass only their best candidates and keep {@code max} small per call.
     */
    public void refreshProfiles(List<Integer> itemIds, int max) {
        int fetched = 0;
        for (int id : itemIds) {
            if (fetched >= max) break;
            if (profile(id) != null) continue;
            try {
                profiles.put(id, buildProfile(id, WikiPriceApi.timeseries(id, "24h")));
                fetched++;
            } catch (Exception e) {
                log.debug("[Merchant] Spread profile for {} failed: {}", id, e.getMessage());
                return;
            }
        }
        profiles.values().removeIf(p -> System.currentTimeMillis() - p.fetchedAt > 4 * PROFILE_TTL_MS);
    }

    static SpreadProfile buildProfile(int itemId, List<Average> series) {
        long[] margins = new long[series.size()];
        int n = 0;
        int positive = 0;
        for (Average a : series) {
            if (a.avgHigh <= 0 || a.avgLow <= 0) continue;
            long m = GeTax.afterTax(itemId, Math.round(a.avgHigh)) - Math.round(a.avgLow);
            margins[n++] = m;
            if (m > 0) positive++;
        }
        if (n == 0) return new SpreadProfile(System.currentTimeMillis(), 0, 0, 0);
        long[] used = Arrays.copyOf(margins, n);
        Arrays.sort(used);
        return new SpreadProfile(System.currentTimeMillis(), n, positive / (double) n, used[n / 2]);
    }

    /** Item names to ids, for the few places that work by name. */
    public Map<String, Integer> idsByName() {
        Map<String, Integer> out = new HashMap<>();
        for (ItemMapping m : mapping.values()) out.put(m.name.toLowerCase(), m.id);
        return out;
    }
}
