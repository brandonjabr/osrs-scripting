package net.runelite.client.plugins.microbot.util.market;

import java.util.Set;

/**
 * The Grand Exchange "convenience fee" taken from the seller: 2% of the price per item since 29 May 2025 (1% from
 * December 2021), rounded down, capped at 5m per item. Rounding down makes anything under 50 gp tax-free. A list of
 * low-level items and Old school bonds are exempt.
 */
public final class GeTax {

    public static final double RATE = 0.02;
    public static final long CAP = 5_000_000L;

    // Exempt item ids (bond, low-level food, ammo, runes, teleports and basic tools), as used by Flipping Copilot.
    private static final Set<Integer> EXEMPT = Set.of(
            8011, 365, 2309, 882, 806, 1891, 8010, 1755, 28824, 2140, 2142, 8009, 5325, 1785, 2347, 347,
            884, 807, 28790, 379, 8008, 355, 2327, 558, 1733, 13190, 233, 351, 5341, 2552, 329, 8794, 5329,
            5343, 1735, 315, 952, 886, 808, 8013, 361, 8007, 5331);

    private GeTax() {
    }

    /** Tax on selling one item at {@code price}. */
    public static long tax(int itemId, long price) {
        if (price <= 0 || EXEMPT.contains(itemId)) return 0;
        return Math.min(CAP, (long) Math.floor(price * RATE));
    }

    /** What the seller keeps per item. */
    public static long afterTax(int itemId, long price) {
        return price - tax(itemId, price);
    }

    /** Lowest sell price that gets {@code cost} per item back after tax. */
    public static long breakEvenPrice(int itemId, long cost) {
        // cost / 0.98 rounded up is within a coin or two of the answer; past the cap it's cost + 5m.
        long p = Math.min((long) Math.ceil(cost / (1 - RATE)), cost + CAP);
        while (p > 1 && afterTax(itemId, p - 1) >= cost) p--;
        while (afterTax(itemId, p) < cost) p++;
        return p;
    }
}
