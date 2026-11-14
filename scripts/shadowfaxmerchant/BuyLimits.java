package net.runelite.client.plugins.microbot.shadowfaxmerchant;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks the GE's 4-hour buy limits. The limit window starts with the first item bought and resets four hours
 * later, whatever was bought in between. Saved with the positions so a restart doesn't forget a nearly used limit.
 */
public final class BuyLimits {

    static final long WINDOW_MS = 4 * 60 * 60 * 1000L;

    /** Item id → {window start ms, units bought in the window}. Plain map so Gson can save it. */
    public Map<Integer, long[]> windows = new HashMap<>();

    public synchronized void record(int itemId, int quantity, long now) {
        if (quantity <= 0) return;
        long[] w = windows.get(itemId);
        if (w == null || now - w[0] >= WINDOW_MS) {
            windows.put(itemId, new long[]{now, quantity});
        } else {
            w[1] += quantity;
        }
    }

    /** Units still buyable now under {@code limit}. */
    public synchronized int remaining(int itemId, int limit, long now) {
        long[] w = windows.get(itemId);
        if (w == null || now - w[0] >= WINDOW_MS) return limit;
        return (int) Math.max(0, limit - w[1]);
    }

    /** Units bought in the live window; 0 once it has reset. */
    public synchronized int used(int itemId, long now) {
        long[] w = windows.get(itemId);
        return w == null || now - w[0] >= WINDOW_MS ? 0 : (int) w[1];
    }

    public synchronized void prune(long now) {
        windows.values().removeIf(w -> now - w[0] >= WINDOW_MS);
    }
}
